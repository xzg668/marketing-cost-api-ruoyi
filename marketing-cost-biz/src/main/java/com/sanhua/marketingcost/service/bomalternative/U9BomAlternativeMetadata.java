package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.mapper.BomU9SourceMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Restores missing U9 type/group facts on caller-owned hierarchy copies.
 * Never changes quantities, paths, versions or database rows. New monthly snapshots freeze these facts.
 */
@Component
public class U9BomAlternativeMetadata {
  private static final int PARENT_BATCH_SIZE = 128;
  private final BomU9SourceMapper sourceMapper;
  private final BomAlternativeGroupKeyGenerator keys;

  public U9BomAlternativeMetadata(BomU9SourceMapper sourceMapper, BomAlternativeGroupKeyGenerator keys) {
    this.sourceMapper = sourceMapper;
    this.keys = keys;
  }

  public void complete(List<BomRawHierarchy> rows) {
    if (rows == null || rows.isEmpty()) return;
    List<BomRawHierarchy> missing = rows.stream().filter(Objects::nonNull)
        .filter(this::u9Child).filter(row -> !StringUtils.hasText(row.getChildType())).toList();
    Map<SourceLine, List<BomU9Source>> sources = readSources(missing);
    Map<Position, List<BomU9Source>> currentPositions = new LinkedHashMap<>();
    sources.values().stream().flatMap(List::stream).forEach(source ->
        currentPositions.computeIfAbsent(Position.of(SourceLine.of(source)), ignored -> new ArrayList<>()).add(source));
    Map<BomRawHierarchy, String> types = new LinkedHashMap<>();
    for (BomRawHierarchy row : missing) {
      var matches = sources.getOrDefault(SourceLine.of(row), List.of());
      if (matches.isEmpty()) {
        types.put(row, currentType(row, currentPositions));
        continue;
      }
      if (matches.size() != 1) {
        throw unavailable(row, "原始父子来源不唯一");
      }
      var type = BomChildType.fromSource(matches.getFirst().getChildType(), true);
      if (type != BomChildType.STANDARD && type != BomChildType.ALTERNATIVE && type != BomChildType.NORMAL) {
        throw unavailable(row, "来源没有明确的标准／替代类型");
      }
      types.put(row, type.name());
    }
    // Validate all source matches first: a failure must not leave half a group restored.
    types.forEach(BomRawHierarchy::setChildType);
    Map<BomAlternativeGroupIdentity, List<BomRawHierarchy>> positions = new LinkedHashMap<>();
    for (BomRawHierarchy row : rows) {
      if (row != null && u9Child(row)) {
        positions.computeIfAbsent(identity(row), ignored -> new ArrayList<>()).add(row);
      }
    }
    for (var position : positions.entrySet()) {
      var members = position.getValue();
      if (members.stream().noneMatch(row -> !StringUtils.hasText(row.getAlternativeGroupKey()))) continue;
      var existing = new LinkedHashSet<String>();
      members.stream().map(BomRawHierarchy::getAlternativeGroupKey).filter(StringUtils::hasText)
          .map(String::trim).forEach(existing::add);
      if (existing.size() > 1) throw unavailable(members.getFirst(), "同一位置存在冲突的替代组键");
      String key = existing.isEmpty() ? keys.generate(position.getKey()) : existing.iterator().next();
      members.stream().filter(row -> !StringUtils.hasText(row.getAlternativeGroupKey()))
          .forEach(row -> row.setAlternativeGroupKey(key));
    }
  }

  /** Only recover the missing role of this frozen occurrence; never replace its material or quantity. */
  private String currentType(BomRawHierarchy row, Map<Position, List<BomU9Source>> sources) {
    LocalDate date = com.sanhua.marketingcost.util.CostPricingPeriodUtils.currentPricingDate();
    var types = new LinkedHashSet<BomChildType>();
    for (var source : sources.getOrDefault(Position.of(SourceLine.of(row)), List.of())) {
      if (source.getEffectiveFrom() != null && source.getEffectiveFrom().isAfter(date)
          || source.getEffectiveTo() != null && source.getEffectiveTo().isBefore(date)) continue;
      var type = BomChildType.fromSource(source.getChildType(), true);
      if (type != BomChildType.STANDARD && type != BomChildType.ALTERNATIVE && type != BomChildType.NORMAL)
        throw unavailable(row, "有效来源没有明确的标准／替代类型");
      types.add(type);
    }
    if (types.isEmpty()) throw unavailable(row, "没有找到对应父子料号的有效来源");
    if (types.size() != 1) throw unavailable(row, "有效父子来源的标准／替代类型冲突");
    return types.iterator().next().name();
  }

  private record Position(String organization, String parent, String material, String purpose,
      Integer sequence, String process) {
    static Position of(SourceLine line) {
      return new Position(line.organization(), line.parent(), line.material(), line.purpose(), line.sequence(), line.process());
    }
  }

  private Map<SourceLine, List<BomU9Source>> readSources(List<BomRawHierarchy> rows) {
    Map<String, LinkedHashSet<String>> parents = new LinkedHashMap<>();
    for (BomRawHierarchy row : rows) {
      if (!StringUtils.hasText(row.getPriceOrgCode()) || !StringUtils.hasText(row.getParentCode())
          || !StringUtils.hasText(row.getMaterialCode()) || row.getSortSeq() == null
          || !StringUtils.hasText(row.getBomPurpose()) || !StringUtils.hasText(row.getBomVersion())) {
        throw unavailable(row, "缺少组织、父子料号、项次或BOM版本，不能确认替代关系");
      }
      parents.computeIfAbsent(sourceOrganization(row), ignored -> new LinkedHashSet<>()).add(row.getParentCode());
    }
    Map<SourceLine, List<BomU9Source>> result = new LinkedHashMap<>();
    parents.forEach((organization, codes) -> {
      var ordered = new ArrayList<>(codes);
      for (int start = 0; start < ordered.size(); start += PARENT_BATCH_SIZE) {
        var batch = ordered.subList(start, Math.min(start + PARENT_BATCH_SIZE, ordered.size()));
        for (BomU9Source source : sourceMapper.selectAlternativeMetadata(organization, batch)) {
          result.computeIfAbsent(SourceLine.of(source), ignored -> new ArrayList<>()).add(source);
        }
      }
    });
    return result;
  }

  private boolean u9Child(BomRawHierarchy row) {
    return "U9".equalsIgnoreCase(row.getSourceType()) && row.getLevel() != null && row.getLevel() > 0;
  }

  private BomAlternativeGroupIdentity identity(BomRawHierarchy row) {
    String path = row.getPath();
    if (!StringUtils.hasText(path)) throw unavailable(row, "缺少BOM路径");
    String normalized = path.trim().replace('\\', '/').replaceAll("/+", "/");
    if (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    String parentPath = normalized.substring(0, normalized.lastIndexOf('/') + 1);
    return new BomAlternativeGroupIdentity(sourceOrganization(row), row.getTopProductCode(),
        keys.parentPathFingerprint(parentPath), row.getParentCode(), row.getBomPurpose(), row.getBomVersion(),
        row.getEffectiveFrom(), row.getEffectiveTo(), row.getSortSeq(), row.getProcessSeq());
  }

  /**
   * Plate-to-commercial expansion changes the pricing organization, not the source BOM position.
   * EasyData's documented occurrence key retains that original line; accept it only when every
   * business field matches. Opaque legacy keys continue to use the hierarchy organization.
   */
  static String sourceOrganization(BomRawHierarchy row) {
    String organization = SourceLine.text(row.getPriceOrgCode());
    String[] parts = SourceLine.text(row.getSourceLineKey()).split("\\|", -1);
    if ("U9".equalsIgnoreCase(row.getSourceType()) && "210".equals(organization)
        && parts.length == 10 && "220".equals(parts[1])) {
      String expected = String.join("|", parts[0], "220", SourceLine.text(row.getParentCode()),
          SourceLine.text(row.getMaterialCode()), SourceLine.text(row.getBomPurpose()),
          Objects.toString(row.getSortSeq(), ""), SourceLine.text(row.getProcessSeq()),
          SourceLine.text(row.getBomVersion()), Objects.toString(row.getEffectiveFrom(), ""),
          Objects.toString(row.getEffectiveTo(), ""));
      if (!parts[0].matches("[a-fA-F0-9]{32}") || !expected.equals(row.getSourceLineKey())) {
        throw unavailable(row, "跨组织来源行标识与BOM身份不一致");
      }
      return "220";
    }
    return organization;
  }

  private static QuoteBomAlternativeSelectionException unavailable(BomRawHierarchy row, String reason) {
    return new QuoteBomAlternativeSelectionException("ALT_SOURCE_METADATA_MISSING",
        "BOM标准／替代关系无法确认：父件=" + row.getParentCode() + "，料号=" + row.getMaterialCode()
            + "；" + reason + "，请核实U9父子来源资料后重试");
  }

  private record SourceLine(String organization, String parent, String material, String purpose,
      String version, Integer sequence, String process, LocalDate from, LocalDate to) {
    static SourceLine of(BomRawHierarchy row) {
      return new SourceLine(sourceOrganization(row), text(row.getParentCode()), text(row.getMaterialCode()),
          text(row.getBomPurpose()), text(row.getBomVersion()), row.getSortSeq(), text(row.getProcessSeq()),
          row.getEffectiveFrom(), row.getEffectiveTo());
    }
    static SourceLine of(BomU9Source row) {
      return new SourceLine(text(row.getPriceOrgCode()), text(row.getParentMaterialNo()), text(row.getChildMaterialNo()),
          text(row.getBomPurpose()), text(row.getBomVersion()), row.getChildSeq(), text(row.getProcessSeq()),
          row.getEffectiveFrom(), row.getEffectiveTo());
    }
    private static String text(String value) { return value == null ? "" : value.trim(); }
  }
}
