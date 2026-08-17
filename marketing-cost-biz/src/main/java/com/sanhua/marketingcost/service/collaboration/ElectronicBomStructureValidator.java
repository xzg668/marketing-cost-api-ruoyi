package com.sanhua.marketingcost.service.collaboration;

import com.sanhua.marketingcost.integration.drawing.ElectronicBomFetchResult;
import com.sanhua.marketingcost.integration.drawing.ElectronicBomNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 对电子图库事实进行本地完整性校验，不采用上游 valid/complete 判定。 */
@Component
public class ElectronicBomStructureValidator {

  private static final Set<String> ACTIVE_VERSION_STATUSES =
      Set.of("ACTIVE", "EFFECTIVE", "VALID");

  public ValidationResult validate(
      ElectronicBomFetchResult result,
      String expectedProductCode,
      String expectedMaterialOrganization,
      String expectedPurpose,
      LocalDate asOfDate) {
    List<ElectronicBomValidationIssue> issues = new ArrayList<>();
    if (result == null || result.status() != ElectronicBomFetchResult.Status.FOUND) {
      issues.add(issue(null, null, "ELECTRONIC_BOM_RESULT_MISSING", "电子图库没有返回可校验BOM"));
      return new ValidationResult(null, issues);
    }
    checkHeader(result, expectedProductCode, expectedMaterialOrganization, expectedPurpose,
        asOfDate, issues);
    List<ElectronicBomNode> source = result.nodes();
    if (source == null || source.isEmpty()) {
      issues.add(issue(null, null, "BOM_NODE_EMPTY", "电子图库BOM没有任何节点"));
      return new ValidationResult(null, issues);
    }

    Map<String, SourceNode> byKey = new LinkedHashMap<>();
    for (ElectronicBomNode row : source) {
      String key = trim(row == null ? null : row.nodeKey());
      if (key == null) {
        issues.add(issue(null, null, "NODE_KEY_REQUIRED", "BOM节点标识不能为空"));
        continue;
      }
      if (byKey.containsKey(key)) {
        issues.add(issue(key, null, "NODE_KEY_DUPLICATED", "BOM节点标识重复：" + key));
        continue;
      }
      SourceNode node = normalize(row, key, issues);
      byKey.put(key, node);
    }
    if (byKey.isEmpty()) return new ValidationResult(null, issues);

    List<SourceNode> roots = byKey.values().stream()
        .filter(node -> node.parentKey() == null).toList();
    if (roots.size() != 1) {
      issues.add(issue(null, null, "ROOT_COUNT_INVALID", "完整BOM必须且只能有一个根节点"));
      return new ValidationResult(null, issues);
    }
    SourceNode root = roots.getFirst();
    if (!Objects.equals(trim(expectedProductCode), root.materialCode())) {
      issues.add(issue(root.key(), null, "ROOT_PRODUCT_MISMATCH",
          "电子图库BOM根节点必须是当前目标料号"));
    }

    Map<String, List<SourceNode>> children = new LinkedHashMap<>();
    Map<String, Set<String>> siblingMaterials = new HashMap<>();
    for (SourceNode node : byKey.values()) {
      if (node.parentKey() != null && !byKey.containsKey(node.parentKey())) {
        issues.add(issue(node.key(), null, "ORPHAN_NODE",
            "节点找不到父节点：" + node.key()));
        continue;
      }
      if (Objects.equals(node.key(), node.parentKey())) {
        issues.add(issue(node.key(), null, "SELF_PARENT", "节点不能把自己作为父节点"));
        continue;
      }
      if (node.parentKey() != null) {
        children.computeIfAbsent(node.parentKey(), ignored -> new ArrayList<>()).add(node);
        String siblingKey = node.materialCode() == null ? "NODE:" + node.key() : node.materialCode();
        if (!siblingMaterials.computeIfAbsent(node.parentKey(), ignored -> new HashSet<>())
            .add(siblingKey)) {
          issues.add(issue(node.key(), null, "DUPLICATE_SIBLING_MATERIAL",
              "同一父项下存在重复子件：" + siblingKey));
        }
      }
    }
    children.values().forEach(rows -> rows.sort(Comparator
        .comparingInt(SourceNode::sortSeq).thenComparing(SourceNode::key)));
    detectCycles(byKey, children, issues);
    if (issues.stream().anyMatch(value -> Set.of(
        "ORPHAN_NODE", "SELF_PARENT", "BOM_CYCLE").contains(value.code()))) {
      return new ValidationResult(null, issues);
    }

    for (SourceNode node : byKey.values()) {
      int childCount = children.getOrDefault(node.key(), List.of()).size();
      if ("PURCHASE".equals(node.nature()) && childCount > 0) {
        issues.add(issue(node.key(), null, "PURCHASE_HAS_CHILDREN",
            "采购件不能继续挂下级：" + display(node)));
      }
      if (Set.of("MANUFACTURE", "OUTSOURCE", "VIRTUAL_PACKAGE").contains(node.nature())
          && childCount == 0) {
        issues.add(issue(node.key(), null, "CHILD_REQUIRED",
            natureLabel(node.nature()) + "必须继续补下级：" + display(node)));
      }
    }

    List<ValidatedElectronicBom.Node> normalized = new ArrayList<>();
    walk(root, null, 0, BigDecimal.ONE, "/", children, normalized, new LinkedHashSet<>());
    if (normalized.size() != byKey.size()) {
      issues.add(issue(null, null, "UNREACHABLE_NODE", "BOM存在根节点不可到达的节点"));
    }
    Map<String, ValidatedElectronicBom.Node> normalizedByKey = new HashMap<>();
    normalized.forEach(node -> normalizedByKey.put(node.nodeKey(), node));
    for (SourceNode sourceNode : byKey.values()) {
      ValidatedElectronicBom.Node node = normalizedByKey.get(sourceNode.key());
      if (node != null && sourceNode.reportedLevel() != null
          && sourceNode.reportedLevel() != node.level()) {
        issues.add(issue(node.nodeKey(), node.path(), "LEVEL_MISMATCH",
            "节点层级与父子关系计算结果不一致"));
      }
    }
    if (!issues.isEmpty()) return new ValidationResult(null, issues);
    return new ValidationResult(new ValidatedElectronicBom(
        trim(result.sourceSystem()), trim(result.productCode()),
        trim(result.materialOrganizationCode()), trim(result.bomPurpose()),
        trim(result.sourceVersion()), trim(result.versionStatus()), result.effectiveFrom(),
        result.effectiveTo(), result.queriedAt(), normalized), List.of());
  }

  private void checkHeader(
      ElectronicBomFetchResult result,
      String expectedProductCode,
      String expectedMaterialOrganization,
      String expectedPurpose,
      LocalDate asOfDate,
      List<ElectronicBomValidationIssue> issues) {
    if (trim(result.sourceSystem()) == null) {
      issues.add(issue(null, null, "SOURCE_SYSTEM_REQUIRED", "电子图库来源系统不能为空"));
    }
    if (!Objects.equals(trim(expectedProductCode), trim(result.productCode()))) {
      issues.add(issue(null, null, "PRODUCT_MISMATCH", "电子图库返回料号与当前目标料号不一致"));
    }
    if (trim(result.materialOrganizationCode()) == null) {
      issues.add(issue(null, null, "MATERIAL_ORG_REQUIRED", "电子图库返回缺少物料组织"));
    } else if (trim(expectedMaterialOrganization) != null
        && !Objects.equals(trim(expectedMaterialOrganization), trim(result.materialOrganizationCode()))) {
      issues.add(issue(null, null, "MATERIAL_ORG_MISMATCH", "电子图库返回物料组织与当前任务不一致"));
    }
    if (trim(result.bomPurpose()) == null) {
      issues.add(issue(null, null, "BOM_PURPOSE_REQUIRED", "电子图库返回缺少BOM用途"));
    } else if (trim(expectedPurpose) != null
        && !Objects.equals(trim(expectedPurpose), trim(result.bomPurpose()))) {
      issues.add(issue(null, null, "BOM_PURPOSE_MISMATCH", "电子图库返回BOM用途与本次查询不一致"));
    }
    if (trim(result.sourceVersion()) == null) {
      issues.add(issue(null, null, "SOURCE_VERSION_REQUIRED", "电子图库返回缺少BOM版本"));
    }
    String versionStatus = upper(result.versionStatus());
    if (!ACTIVE_VERSION_STATUSES.contains(versionStatus)) {
      issues.add(issue(null, null, "VERSION_NOT_ACTIVE", "电子图库BOM版本不是当前有效状态"));
    }
    LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
    if (result.effectiveFrom() == null || result.effectiveFrom().isAfter(date)
        || result.effectiveTo() != null && result.effectiveTo().isBefore(date)) {
      issues.add(issue(null, null, "VERSION_NOT_EFFECTIVE", "电子图库BOM在当前日期未生效"));
    }
    if (result.queriedAt() == null) {
      issues.add(issue(null, null, "QUERY_TIME_REQUIRED", "电子图库返回缺少查询时间"));
    }
  }

  private SourceNode normalize(
      ElectronicBomNode row,
      String key,
      List<ElectronicBomValidationIssue> issues) {
    String parent = trim(row.parentNodeKey());
    String material = trim(row.materialCode());
    String name = trim(row.materialName());
    String unit = trim(row.unit());
    String nature = nature(row.materialNature());
    BigDecimal quantity = row.quantityPerParent();
    if (material == null) issues.add(issue(key, null, "MATERIAL_CODE_REQUIRED", "节点料号不能为空"));
    if (name == null) issues.add(issue(key, null, "MATERIAL_NAME_REQUIRED", "节点名称不能为空"));
    if (unit == null) issues.add(issue(key, null, "UNIT_REQUIRED", "节点单位不能为空"));
    if (nature == null) issues.add(issue(key, null, "MATERIAL_NATURE_INVALID",
        "物料性质仅支持采购件、制造件、委外件、虚拟件（包装）"));
    if (quantity == null || quantity.compareTo(BigDecimal.ZERO) <= 0) {
      issues.add(issue(key, null, "QUANTITY_INVALID", "节点用量必须大于0"));
    }
    if (Boolean.FALSE.equals(row.active())) {
      issues.add(issue(key, null, "NODE_NOT_ACTIVE", "电子图库节点已失效"));
    }
    return new SourceNode(key, parent, row.level(), material, name, trim(row.materialSpec()),
        trim(row.materialModel()), trim(row.drawingNo()), nature, quantity, unit,
        row.sortSeq() == null ? Integer.MAX_VALUE : row.sortSeq());
  }

  private void detectCycles(
      Map<String, SourceNode> byKey,
      Map<String, List<SourceNode>> children,
      List<ElectronicBomValidationIssue> issues) {
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String key : byKey.keySet()) detectCycle(key, children, visiting, visited, issues);
  }

  private void detectCycle(
      String key,
      Map<String, List<SourceNode>> children,
      Set<String> visiting,
      Set<String> visited,
      List<ElectronicBomValidationIssue> issues) {
    if (visited.contains(key)) return;
    if (!visiting.add(key)) {
      issues.add(issue(key, null, "BOM_CYCLE", "BOM存在循环关系：" + key));
      return;
    }
    for (SourceNode child : children.getOrDefault(key, List.of())) {
      detectCycle(child.key(), children, visiting, visited, issues);
    }
    visiting.remove(key);
    visited.add(key);
  }

  private void walk(
      SourceNode node,
      SourceNode parent,
      int level,
      BigDecimal parentToTop,
      String parentPath,
      Map<String, List<SourceNode>> children,
      List<ValidatedElectronicBom.Node> output,
      Set<String> visiting) {
    if (!visiting.add(node.key())) return;
    BigDecimal quantity = level == 0 ? BigDecimal.ONE : node.quantity();
    BigDecimal quantityToTop = level == 0 ? BigDecimal.ONE : parentToTop.multiply(quantity);
    String path = parentPath + node.materialCode() + "/";
    output.add(new ValidatedElectronicBom.Node(node.key(), node.parentKey(), level,
        parent == null ? null : parent.materialCode(), node.materialCode(), node.materialName(),
        node.materialSpec(), node.materialModel(), node.drawingNo(), node.nature(), quantity,
        quantityToTop, node.unit(), node.sortSeq(), path));
    for (SourceNode child : children.getOrDefault(node.key(), List.of())) {
      walk(child, node, level + 1, quantityToTop, path, children, output, visiting);
    }
    visiting.remove(node.key());
  }

  private static String nature(String value) {
    String normalized = trim(value);
    if (normalized == null) return null;
    String upper = normalized.toUpperCase(Locale.ROOT);
    if (upper.contains("PURCHASE") || normalized.contains("采购")) return "PURCHASE";
    if (upper.contains("OUTSOURCE") || normalized.contains("委外")) return "OUTSOURCE";
    if (upper.contains("VIRTUAL") || upper.contains("PACKAGE")
        || normalized.contains("虚拟") || normalized.contains("包装")) return "VIRTUAL_PACKAGE";
    if (upper.contains("MANUFACTURE") || normalized.contains("制造")
        || normalized.contains("半成品")) return "MANUFACTURE";
    return null;
  }

  private static String natureLabel(String value) {
    return switch (value == null ? "" : value) {
      case "MANUFACTURE" -> "制造件";
      case "OUTSOURCE" -> "委外件";
      case "VIRTUAL_PACKAGE" -> "虚拟件（包装）";
      default -> "物料";
    };
  }

  private static String display(SourceNode node) {
    return node.materialName() == null ? node.materialCode() : node.materialName();
  }

  private static String upper(String value) {
    String normalized = trim(value);
    return normalized == null ? "" : normalized.toUpperCase(Locale.ROOT);
  }

  private static String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static ElectronicBomValidationIssue issue(
      String nodeKey, String path, String code, String message) {
    return new ElectronicBomValidationIssue(nodeKey, path, code, message);
  }

  public record ValidationResult(
      ValidatedElectronicBom bom,
      List<ElectronicBomValidationIssue> issues) {
    public ValidationResult {
      issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean passed() {
      return bom != null && issues.isEmpty();
    }
  }

  private record SourceNode(
      String key,
      String parentKey,
      Integer reportedLevel,
      String materialCode,
      String materialName,
      String materialSpec,
      String materialModel,
      String drawingNo,
      String nature,
      BigDecimal quantity,
      String unit,
      int sortSeq) {}
}
