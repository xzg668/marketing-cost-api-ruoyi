package com.sanhua.marketingcost.integration.drawing;

import com.sanhua.marketingcost.dto.quotebom.FormalBomReadResult;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomSourceLineDto;
import com.sanhua.marketingcost.entity.BomU9Source;
import com.sanhua.marketingcost.service.FormalBomReadService;
import com.sanhua.marketingcost.service.MakePartSourceDataService;
import com.sanhua.marketingcost.service.electronicdrawing.ElectronicDrawingU9SubBomPort;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/** 使用现有正式报价 BOM 读取能力查询电子图库制造节点的 U9 子 BOM。 */
@Component
public class FormalElectronicDrawingU9SubBomAdapter implements ElectronicDrawingU9SubBomPort {
  private static final int MAX_SOURCE_DEPTH = 64;
  private static final int MAX_SOURCE_NODES = 10_000;
  private final FormalBomReadService formalBomReadService;
  private final MakePartSourceDataService sourceDataService;

  public FormalElectronicDrawingU9SubBomAdapter(
      FormalBomReadService formalBomReadService,
      MakePartSourceDataService sourceDataService) {
    this.formalBomReadService = formalBomReadService;
    this.sourceDataService = sourceDataService;
  }

  @Override
  public SubBomResult query(SubBomQuery query) {
    String parentCode = text(query == null ? null : query.parentMaterialCode());
    if (query == null || parentCode == null) {
      return SubBomResult.failure(Status.ERROR, parentCode, "U9 子 BOM 查询父件不能为空");
    }
    try {
      FormalBomReadResult result = formalBomReadService.read(new QuoteBomReadContext(
          query.oaNo(), query.oaFormItemId(), parentCode, query.periodMonth(),
          query.priceOrgCode(), query.materialOrganizationCode(), query.businessUnitType(),
          query.bomPurpose(), query.effectiveDate()));
      if (result == null) {
        return SubBomResult.failure(Status.ERROR, parentCode, "U9 正式 BOM 读取返回空对象");
      }
      List<QuoteBomSourceLineDto> lines = result.lines() == null ? List.of() : result.lines();
      if (!result.found()) {
        String gap = text(result.gapMessage());
        if (gap != null && gap.contains("未在 lp_bom_raw_hierarchy 找到正式 BOM")) {
          return queryCurrentU9Source(query, parentCode, gap);
        }
        if (organizationMismatch(gap)) {
          return SubBomResult.failure(Status.ORGANIZATION_MISMATCH, parentCode, gap);
        }
        return SubBomResult.failure(
            Status.ERROR, parentCode, gap == null ? "U9 正式 BOM 返回无效空结果" : gap);
      }
      if (lines.isEmpty()) {
        return SubBomResult.failure(Status.ERROR, parentCode, "U9 返回有 BOM 但明细为空");
      }
      List<QuoteBomSourceLineDto> roots = lines.stream()
          .filter(line -> line != null && line.level() != null && line.level() == 0)
          .toList();
      if (roots.size() > 1) {
        return SubBomResult.failure(Status.MULTIPLE, parentCode, "U9 返回多个当前有效根节点");
      }
      if (roots.size() != 1 || !same(parentCode, roots.getFirst().materialCode())) {
        return SubBomResult.failure(Status.ERROR, parentCode, "U9 子 BOM 缺少唯一且匹配的根节点");
      }
      for (QuoteBomSourceLineDto line : lines) {
        if (line == null
            || !same(query.priceOrgCode(), line.priceOrgCode())
            || !same(query.materialOrganizationCode(), line.materialOrganizationCode())) {
          return SubBomResult.failure(
              Status.ORGANIZATION_MISMATCH, parentCode, "U9 子 BOM 明细组织与任务不一致");
        }
      }
      QuoteBomSourceLineDto root = roots.getFirst();
      String rootPath = path(root.path());
      if (rootPath == null || root.sourceRawHierarchyId() == null) {
        return SubBomResult.failure(Status.ERROR, parentCode, "U9 根节点缺少路径或来源层级ID");
      }
      List<QuoteBomSourceLineDto> descendants = lines.stream()
          .filter(line -> line != root)
          .sorted(lineComparator())
          .toList();
      if (descendants.isEmpty()) {
        return SubBomResult.failure(Status.ERROR, parentCode, "U9 返回根节点但没有子件");
      }
      Map<String, QuoteBomSourceLineDto> byPath = new LinkedHashMap<>();
      byPath.put(rootPath, root);
      List<U9Node> nodes = new ArrayList<>();
      for (QuoteBomSourceLineDto line : descendants) {
        String currentPath = path(line.path());
        if (currentPath == null || line.sourceRawHierarchyId() == null) {
          return SubBomResult.failure(Status.ERROR, parentCode, "U9 子件缺少路径或来源层级ID");
        }
        if (byPath.put(currentPath, line) != null) {
          return SubBomResult.failure(Status.MULTIPLE, parentCode, "U9 子 BOM 路径重复");
        }
        String parentPath = parentPath(currentPath);
        QuoteBomSourceLineDto parent = byPath.get(parentPath);
        if (parent == null) {
          return SubBomResult.failure(Status.ERROR, parentCode, "U9 子 BOM 存在孤儿路径");
        }
        nodes.add(new U9Node(
            nodeKey(line), parent == root ? null : nodeKey(parent),
            line.sourceRawHierarchyId(), line.sourceU9BomId(), line.materialCode(),
            line.materialName(), line.materialSpec(), line.materialModel(), line.drawingNo(),
            line.shapeAttr(), line.mainCategoryCode(), line.sourceCategory(),
            line.costElementCode(), line.bomPurpose(), line.bomVersion(),
            line.qtyPerParent(), line.parentBaseQty(), line.unit(), line.sortSeq()));
      }
      return SubBomResult.available(
          parentCode, query.priceOrgCode(), query.materialOrganizationCode(), nodes);
    } catch (TransientDataAccessException exception) {
      return SubBomResult.failure(Status.TIMEOUT, parentCode,
          "U9 子 BOM 查询超时：" + message(exception));
    } catch (RuntimeException exception) {
      String message = message(exception);
      if (timeout(message)) {
        return SubBomResult.failure(Status.TIMEOUT, parentCode, "U9 子 BOM 查询超时：" + message);
      }
      if (organizationMismatch(message)) {
        return SubBomResult.failure(Status.ORGANIZATION_MISMATCH, parentCode, message);
      }
      return SubBomResult.failure(Status.ERROR, parentCode, "U9 子 BOM 查询失败：" + message);
    }
  }

  /**
   * 原始层只会为已经进入报价的顶层产品建立快照。电子图库分支中的制造件可能尚未成为
   * 顶层产品，因此正式原始层查无结果时，继续复用现有 U9 ODS 直接子项读取服务。
   */
  private SubBomResult queryCurrentU9Source(
      SubBomQuery query, String parentCode, String formalGap) {
    List<BomU9Source> roots = sourceDataService.listDedupedChildren(
        parentCode, query.effectiveDate(), query.priceOrgCode());
    if (roots == null || roots.isEmpty()) {
      return SubBomResult.failure(Status.NOT_FOUND, parentCode, formalGap);
    }
    List<U9Node> nodes = new ArrayList<>();
    appendCurrentU9Source(
        query, parentCode, null, roots, new LinkedHashSet<>(), new HashSet<>(), nodes, 0);
    return SubBomResult.available(
        parentCode, query.priceOrgCode(), query.materialOrganizationCode(), nodes);
  }

  private void appendCurrentU9Source(
      SubBomQuery query,
      String parentMaterialCode,
      String parentNodeKey,
      List<BomU9Source> rows,
      Set<String> ancestors,
      Set<Long> sourceIds,
      List<U9Node> nodes,
      int depth) {
    if (depth > MAX_SOURCE_DEPTH) {
      throw new IllegalStateException("U9 子 BOM 超过最大层级");
    }
    String normalizedParent = normalize(parentMaterialCode);
    if (!ancestors.add(normalizedParent)) {
      throw new IllegalStateException("U9 子 BOM 存在循环料号：" + parentMaterialCode);
    }
    for (BomU9Source row : rows) {
      if (row == null || row.getId() == null || row.getId() <= 0
          || !sourceIds.add(row.getId())) {
        throw new IllegalStateException("U9 子 BOM 缺少唯一来源行");
      }
      if (nodes.size() >= MAX_SOURCE_NODES) {
        throw new IllegalStateException("U9 子 BOM 超过最大节点数");
      }
      String materialCode = required(row.getChildMaterialNo(), "U9 子件料号");
      String nodeKey = "U9SRC:" + row.getId();
      String nature = nature(row);
      nodes.add(new U9Node(
          nodeKey, parentNodeKey, null, row.getId(), materialCode,
          text(row.getChildMaterialName()), text(row.getChildMaterialSpec()), null, null,
          nature, text(row.getMaterialCategory1()), text(row.getProductionCategory()),
          text(row.getCostElementCode()), text(row.getBomPurpose()), text(row.getBomVersion()),
          row.getQtyPerParent(), row.getParentBaseQty(), unit(row), row.getChildSeq()));
      if (!isPurchase(nature)) {
        List<BomU9Source> children = sourceDataService.listDedupedChildren(
            materialCode, query.effectiveDate(), query.priceOrgCode());
        if (children != null && !children.isEmpty()) {
          appendCurrentU9Source(
              query, materialCode, nodeKey, children, ancestors, sourceIds, nodes, depth + 1);
        }
      }
    }
    ancestors.remove(normalizedParent);
  }

  private static String nature(BomU9Source row) {
    if (Integer.valueOf(1).equals(row.getIsVirtual())) return "虚拟件";
    String nature = firstText(row.getShapeAttr(), row.getProductionCategory());
    if (nature == null) throw new IllegalStateException("U9 子件物料形态为空");
    return nature;
  }

  private static boolean isPurchase(String nature) {
    String normalized = nature.toUpperCase(Locale.ROOT);
    return normalized.contains("PURCHASE") || nature.contains("采购");
  }

  private static String unit(BomU9Source row) {
    String unit = firstText(row.getStockUnit(), row.getIssueUnit());
    if (unit == null) throw new IllegalStateException("U9 子件单位为空");
    return unit;
  }

  private static Comparator<QuoteBomSourceLineDto> lineComparator() {
    return Comparator
        .comparing((QuoteBomSourceLineDto line) ->
            line.level() == null ? Integer.MAX_VALUE : line.level())
        .thenComparing(line -> path(line.path()), Comparator.nullsLast(String::compareTo))
        .thenComparing(line -> line.sortSeq() == null ? Integer.MAX_VALUE : line.sortSeq())
        .thenComparing(line ->
            line.sourceRawHierarchyId() == null ? Long.MAX_VALUE : line.sourceRawHierarchyId());
  }

  private static String nodeKey(QuoteBomSourceLineDto line) {
    return "RAW:" + line.sourceRawHierarchyId();
  }

  private static String path(String value) {
    String normalized = text(value);
    if (normalized == null) return null;
    String result = normalized.startsWith("/") ? normalized : "/" + normalized;
    return result.endsWith("/") ? result : result + "/";
  }

  private static String parentPath(String value) {
    String normalized = path(value);
    if (normalized == null || "/".equals(normalized)) return null;
    String withoutLast = normalized.substring(0, normalized.length() - 1);
    int index = withoutLast.lastIndexOf('/');
    return index < 0 ? null : withoutLast.substring(0, index + 1);
  }

  private static boolean same(String left, String right) {
    String a = text(left);
    String b = text(right);
    return a != null && b != null && a.equalsIgnoreCase(b);
  }

  private static boolean timeout(String message) {
    String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
    return normalized.contains("timeout") || normalized.contains("超时");
  }

  private static boolean organizationMismatch(String message) {
    return message != null && message.contains("组织")
        && (message.contains("不一致") || message.contains("不匹配"));
  }

  private static String message(RuntimeException exception) {
    String value = text(exception.getMessage());
    return value == null ? exception.getClass().getSimpleName() : value;
  }

  private static String text(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String required(String value, String label) {
    String normalized = text(value);
    if (normalized == null) throw new IllegalStateException(label + "不能为空");
    return normalized;
  }

  private static String firstText(String first, String second) {
    String value = text(first);
    return value == null ? text(second) : value;
  }

  private static String normalize(String value) {
    return required(value, "U9 料号").toUpperCase(Locale.ROOT);
  }
}
