package com.sanhua.marketingcost.service.platematerial;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PlateMaterialMapping;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.PlateMaterialMappingMapper;
import com.sanhua.marketingcost.mapper.PlateMaterialMasterQuery;
import com.sanhua.marketingcost.mapper.PlateMaterialMasterQuery.Match;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** The sole configuration-first rule; reads never save or amend a product version. */
@Service
public class PlateMaterialResolver {
  private static final String PLATE_DIVISION = "板换事业部";
  private static final int QUERY_BATCH = 500;

  public record Context(String businessUnitType, String model, String materialOrganizationCode) {
    public Context {
      PlateMaterialMappingService.requireUnit(businessUnitType);
      model = PlateMaterialModel.normalize(model);
      materialOrganizationCode = MaterialOrganization.fromCode(materialOrganizationCode).getCode();
    }
  }

  /** MANUAL_REQUIRED has no initial value; a saved snapshot requires a validated choice. */
  public record Decision(PlateMaterialMode mode, PlateMaterial value,
      PlateMaterialSnapshot.Source source, String revision, List<Match> masterMatches) {}
  private record ConfigurationKey(String unit, String model) {}
  private record MasterKey(String organization, String model) {}
  private record RevisionContent(PlateMaterialMode mode, PlateMaterial value,
      PlateMaterialSnapshot.Source source, List<Match> masterMatches) {}

  private final PlateMaterialMappingMapper mappings;
  private final PlateMaterialMasterQuery master;
  private final ObjectMapper json;

  public PlateMaterialResolver(PlateMaterialMappingMapper mappings, PlateMaterialMasterQuery master, ObjectMapper json) {
    this.mappings = mappings;
    this.master = master;
    this.json = json;
  }

  public Decision resolve(Context context) {
    return resolveAll(List.of(Objects.requireNonNull(context, "板片材质产品上下文不能为空"))).get(context);
  }

  /** Deduplicate identities per request, then batch by tenant and current material organization. */
  public Map<Context, Decision> resolveAll(List<Context> contexts) {
    Objects.requireNonNull(contexts, "板片材质产品上下文不能为空");
    List<Context> unique = contexts.stream().map(Objects::requireNonNull).distinct().toList();
    Map<ConfigurationKey, PlateMaterialMapping> configured = readConfigurations(unique);
    var unresolved = unique.stream().filter(c -> c.model() != null)
        .filter(c -> !configured.containsKey(new ConfigurationKey(c.businessUnitType(), c.model()))).toList();
    Map<MasterKey, List<Match>> matches = readMaster(unresolved);
    Map<Context, Decision> results = new LinkedHashMap<>();
    for (Context context : unique) {
      var mapping = configured.get(new ConfigurationKey(context.businessUnitType(), context.model()));
      if (mapping != null) {
        results.put(context, decision(context, PlateMaterialMode.CONFIG_LOCKED,
            PlateMaterial.parse(mapping.getPlateMaterial()), mapping, List.of()));
        continue;
      }
      var evidence = matches.getOrDefault(new MasterKey(context.materialOrganizationCode(), context.model()), List.of());
      boolean plate = evidence.stream().anyMatch(match -> PLATE_DIVISION.equals(match.productionDivision()));
      results.put(context, decision(context, plate ? PlateMaterialMode.MANUAL_REQUIRED : PlateMaterialMode.HIDDEN,
          null, null, evidence));
    }
    return Map.copyOf(results);
  }

  private Map<ConfigurationKey, PlateMaterialMapping> readConfigurations(List<Context> contexts) {
    Map<String, List<Context>> byUnit = contexts.stream().filter(c -> c.model() != null)
        .collect(Collectors.groupingBy(Context::businessUnitType, TreeMap::new, Collectors.toList()));
    Map<ConfigurationKey, PlateMaterialMapping> rows = new HashMap<>();
    for (var group : byUnit.entrySet()) {
      List<String> models = group.getValue().stream().map(Context::model).distinct().sorted().toList();
      for (int offset = 0; offset < models.size(); offset += QUERY_BATCH) {
        var chunk = models.subList(offset, Math.min(offset + QUERY_BATCH, models.size()));
        for (var row : mappings.findModels(group.getKey(), chunk, false)) {
          rows.put(new ConfigurationKey(row.getBusinessUnitType(), row.getNormalizedModel()), row);
        }
      }
    }
    return rows;
  }

  private Map<MasterKey, List<Match>> readMaster(List<Context> contexts) {
    Map<String, List<Context>> byOrganization = contexts.stream()
        .collect(Collectors.groupingBy(Context::materialOrganizationCode, TreeMap::new, Collectors.toList()));
    Map<MasterKey, List<Match>> rows = new HashMap<>();
    for (var group : byOrganization.entrySet()) {
      List<String> models = group.getValue().stream().map(Context::model).distinct().sorted().toList();
      for (int offset = 0; offset < models.size(); offset += QUERY_BATCH) {
        var chunk = models.subList(offset, Math.min(offset + QUERY_BATCH, models.size()));
        var found = master.findActiveModels(group.getKey(), chunk).stream()
            .collect(Collectors.groupingBy(Match::model));
        found.forEach((model, evidence) -> rows.put(new MasterKey(group.getKey(), model), evidence));
      }
    }
    return rows;
  }

  private Decision decision(Context context, PlateMaterialMode mode, PlateMaterial value,
      PlateMaterialMapping mapping, List<Match> evidence) {
    List<Match> sorted = evidence.stream().sorted(Comparator.comparing(Match::id)).toList();
    List<Long> plateIds = sorted.stream().filter(match -> PLATE_DIVISION.equals(match.productionDivision())).map(Match::id).toList();
    var source = new PlateMaterialSnapshot.Source(context.businessUnitType(), context.model(), context.materialOrganizationCode(),
        mapping == null ? null : mapping.getId(), mapping == null ? null : mapping.getRowVersion(), plateIds);
    try {
      byte[] contents = json.writeValueAsBytes(new RevisionContent(mode, value, source, sorted));
      String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
      return new Decision(mode, value, source, revision, sorted);
    } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
      throw new IllegalStateException("板片材质来源版本生成失败", ex);
    }
  }
}
