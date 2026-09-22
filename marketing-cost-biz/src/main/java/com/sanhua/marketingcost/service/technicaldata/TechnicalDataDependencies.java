package com.sanhua.marketingcost.service.technicaldata;

import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.Dependency;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechModule;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 冻结实际引用依据；依据变化时保留原审批记录，由财务定向退回受影响人员。 */
@Service
public class TechnicalDataDependencies {
  private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TechnicalDataDependencies.class);
  public record Issue(String moduleType, String sourceModuleType, String message) {}
  private static final Map<String, List<String>> INPUTS = Map.of(
      "MANUFACTURING", List.of("DRAWING_BOM"),
      "PRICE", List.of("DRAWING_BOM", "MANUFACTURING", "PACKAGE", "SOLDER"));
  private static final Set<String> READY = Set.of("READY", "RETURNED", "FROZEN", "SUBMITTED", "APPROVED");
  private final QuoteTechnicalDataRepository repository;
  private final TechnicalDataVersionContentCodec content;
  private final OaMessageCodec json;

  public TechnicalDataDependencies(QuoteTechnicalDataRepository repository,
      TechnicalDataVersionContentCodec content, OaMessageCodec json) {
    this.repository = repository; this.content = content; this.json = json;
  }

  public List<Issue> pending(List<QuoteTechModule> modules, Set<String> scope) {
    List<Issue> issues = new ArrayList<>();
    for (String type : scope) for (String input : INPUTS.getOrDefault(type, List.of())) {
      var source = module(modules, input);
      if (!ready(source)) {
        issues.add(new Issue(type, input, name(type) + "引用的" + name(input) + "尚未补齐，请先确定基础资料"));
      }
    }
    return issues;
  }

  public List<Dependency> capture(List<QuoteTechModule> modules, Set<String> scope) {
    var pending = pending(modules, scope);
    if (!pending.isEmpty()) throw new TechnicalDataTaskException(TechnicalDataTaskErrorCode.VERSION_CONFLICT, pending.getFirst().message());
    List<Dependency> result = new ArrayList<>();
    for (String type : scope.stream().sorted().toList()) for (String input : INPUTS.getOrDefault(type, List.of())) {
      var source = module(modules, input);
      result.add(new Dependency(type, input, source.getCurrentVersionId(), json.dataFingerprint(basis(type, source))));
    }
    return result;
  }

  public List<Issue> stale(QuoteTechDataVersion frozen, List<QuoteTechModule> modules) {
    var snapshot = content.supplementContent(frozen);
    var dependencies = snapshot == null || snapshot.sources() == null ? null : snapshot.sources().dependencies();
    if (dependencies == null || dependencies.isEmpty()) return List.of();
    List<Issue> result = new ArrayList<>();
    for (var dependency : dependencies) {
      var source = module(modules, dependency.sourceModuleType());
      Object currentBasis = ready(source) ? basis(dependency.moduleType(), source) : null;
      String currentHash = currentBasis == null ? null : json.dataFingerprint(currentBasis);
      if (!ready(source)
          || !json.matchesDataFingerprint(dependency.contentHash(), currentBasis)) {
        log.warn("Technical dependency changed: version={}, module={}, sourceModule={}, expected={}, actual={}",
            frozen.getId(), dependency.moduleType(), dependency.sourceModuleType(), dependency.contentHash(), currentHash);
        result.add(new Issue(dependency.moduleType(), dependency.sourceModuleType(), name(dependency.moduleType())
            + "引用的" + name(dependency.sourceModuleType()) + "已变化或尚未补齐，请检查并退回受影响人员重新提交"));
      }
    }
    return result;
  }

  public List<Issue> approvedIssues(List<QuoteTechModule> modules) {
    Map<Long, QuoteTechDataVersion> versions = new LinkedHashMap<>();
    modules.stream().filter(module -> "APPROVED".equals(module.getModuleStatus()) && module.getCurrentVersionId() != null)
        .forEach(module -> versions.computeIfAbsent(module.getCurrentVersionId(), id -> repository.findVersion(id).orElseThrow()));
    return versions.values().stream().flatMap(version -> stale(version, modules).stream()).distinct().toList();
  }

  private Object basis(String target, QuoteTechModule source) {
    if (!Integer.valueOf(1).equals(source.getRequiredFlag())) {
      return Map.of("availability", source.getSourceAvailability(),
          "sourceReference", Objects.toString(source.getSourceReference(), ""));
    }
    var version = repository.findVersion(source.getCurrentVersionId()).orElseThrow();
    var snapshot = content.supplementContent(version);
    // 单价依据是料号和单位；单纯调整数量不应使另一人的已批单价作废。
    Object basis = switch (source.getModuleType()) {
      case "DRAWING_BOM" -> "PRICE".equals(target)
          ? snapshot.drawingBom().nodes().stream().map(node -> key(node.materialNo(), node.unit())).distinct().sorted().toList()
          : snapshot.drawingBom();
      case "MANUFACTURING" -> snapshot.manufacturing().items().stream()
          .flatMap(item -> java.util.stream.Stream.concat(java.util.stream.Stream.of(key(item.rawMaterialNo(), item.unit())),
              item.evidence() == null || item.evidence().scrapMappings() == null ? java.util.stream.Stream.empty()
                  : item.evidence().scrapMappings().stream().map(scrap -> key(scrap.materialNo(), scrap.unit()))))
          .distinct().sorted().toList();
      case "SOLDER" -> snapshot.solder().items().stream()
          .map(item -> key(item.materialNo(), item.unit())).distinct().sorted().toList();
      case "PACKAGE" -> repository.findPackageItems(version.getId()).stream()
          .map(item -> key(item.getComponentMaterialNo(), item.getOriginalUnit())).distinct().sorted().toList();
      default -> throw new IllegalArgumentException("未定义的资料依赖：" + source.getModuleType());
    };
    return basis;
  }

  private String key(String material, String unit) { return Objects.toString(material, "") + "|" + Objects.toString(unit, ""); }
  private boolean ready(QuoteTechModule source) {
    return source != null && Set.of("AVAILABLE", "MISSING").contains(Objects.toString(source.getSourceAvailability(), ""))
        && (!Integer.valueOf(1).equals(source.getRequiredFlag()) || READY.contains(source.getModuleStatus()));
  }
  private QuoteTechModule module(List<QuoteTechModule> modules, String type) {
    return modules.stream().filter(module -> type.equals(module.getModuleType())).findFirst().orElse(null);
  }
  private String name(String type) {
    return switch (type) { case "PRICE" -> "价格"; case "DRAWING_BOM" -> "电子图库明细";
      case "MANUFACTURING" -> "制造件"; case "PACKAGE" -> "包装"; case "SOLDER" -> "焊料"; default -> type; };
  }
}
