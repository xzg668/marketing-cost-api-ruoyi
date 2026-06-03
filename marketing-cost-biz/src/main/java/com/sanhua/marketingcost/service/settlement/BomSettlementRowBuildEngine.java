package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomSettlementRuleMatcher;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 统一 BOM 结算行生成引擎；只做内存候选生成，不写结算行、不替换现有生产调用。 */
@Component
public class BomSettlementRowBuildEngine {

  public static final String ACTION_EXCLUDE = "EXCLUDE";
  public static final String ACTION_ROLLUP_TO_PARENT = "ROLLUP_TO_PARENT";
  public static final String ACTION_STOP_AS_PACKAGE = "STOP_AS_PACKAGE";
  public static final String ACTION_ADD_PROCESS_FEE = "ADD_PROCESS_FEE";
  public static final String ROW_TYPE_DEFAULT_LEAF = "DEFAULT_LEAF";
  public static final String ROW_TYPE_PACKAGE_PARENT = "PACKAGE_PARENT";
  public static final String ROW_TYPE_SPECIAL_ROLLUP_PARENT = "SPECIAL_ROLLUP_PARENT";
  public static final String ROW_TYPE_PROCESS_FEE = "OUTSOURCED_PROCESS_FEE";
  public static final String ROW_TYPE_BYPRODUCT_EXTRA = "BYPRODUCT_EXTRA";
  public static final String REF_TYPE_SPECIAL_ROLLUP_CHILD = "SPECIAL_ROLLUP_CHILD";
  private static final String ADD_CONDITION_NO_SCRAP_REF_MATCH = "NO_SCRAP_REF_MATCH";
  private static final String RULE_CATEGORY_SPECIAL_PURCHASE_ROLLUP = "SPECIAL_PURCHASE_ROLLUP";
  private static final String RULE_CATEGORY_AUXILIARY_EXCLUDE = "AUXILIARY_EXCLUDE";
  private static final String BOM_PURPOSE_MAIN_MANUFACTURING = "主制造";
  private static final String SHAPE_VIRTUAL = "虚拟";
  private static final String SHAPE_MANUFACTURED = "制造件";
  private static final String SHAPE_PURCHASED = "采购件";
  private static final String SHAPE_OUTSOURCED = "委外加工件";
  private static final String SHAPE_OUTSOURCED_SHORT = "委外件";
  private static final String CATEGORY_PACKAGE_COMPONENT_PREFIX = "15155";
  private static final String CATEGORY_AUXILIARY_PREFIX = "18";

  private final BomSettlementRuleMatcher ruleMatcher;
  private final BomByproductCostRuleMatcher byproductRuleMatcher;

  public BomSettlementRowBuildEngine(
      BomSettlementRuleMatcher ruleMatcher,
      BomByproductCostRuleMatcher byproductRuleMatcher) {
    this.ruleMatcher = ruleMatcher;
    this.byproductRuleMatcher = byproductRuleMatcher;
  }

  public BomSettlementRowBuildResult build(BomSettlementBuildRequest request) {
    List<String> warnings = new ArrayList<>();
    if (request == null) {
      warnings.add("REQUEST_NULL: BOM 结算行生成请求为空");
      return new BomSettlementRowBuildResult(
          List.of(),
          List.of(),
          List.of(),
          warnings,
          new BomSettlementRowBuildStats(0, 0, 0, 0, warnings.size(), 0, 0, 0, 0));
    }
    List<BomSettlementNode> nodes = normalizedNodes(request, warnings);
    Map<String, BomSettlementNode> nodeByPath = indexByPath(nodes, warnings);
    Map<String, List<BomSettlementNode>> childrenByParentPath = indexChildren(nodes);
    validateStructure(nodes, nodeByPath, childrenByParentPath, warnings);

    List<BomCostingRow> normalRows = new ArrayList<>();
    List<BomCostingRow> extraRows = new ArrayList<>();
    List<BomSettlementSourceRefCandidate> sourceRefs = new ArrayList<>();
    Map<String, RollupBucket> rollupBuckets = new LinkedHashMap<>();
    Set<String> stoppedPaths = new LinkedHashSet<>();
    Set<String> consumedLeafPaths = new HashSet<>();
    Set<String> emittedProcessFeePaths = new HashSet<>();

    for (BomSettlementNode node : nodes) {
      if (isUnderStoppedSubtree(node.path(), stoppedPaths) || consumedLeafPaths.contains(node.path())) {
        continue;
      }

      BomSettlementNode parent = nodeByPath.get(parentPathOf(node.path()));
      List<BomSettlementNode> children = childrenByParentPath.getOrDefault(node.path(), List.of());
      Optional<BomSettlementRule> hit = ruleMatcher.match(
          toRuleContext(node, request),
          parent == null ? null : toRuleContext(parent, request),
          children.stream().map(child -> toRuleContext(child, request)).toList(),
          requestedBomPurpose(request, node),
          request.asOfDate(),
          request.settlementRules());

      // 规则执行顺序：先排除，再做上卷，再做包装/停止边界，再做额外附加行，最后才落到默认叶子。
      if (hit.isPresent()) {
        BomSettlementRule rule = hit.get();
        String action = normalize(rule.getSettlementAction());

        if (ACTION_EXCLUDE.equals(action)) {
          if (shouldExcludeNode(node, rule)) {
            // 辅料排除只在 18 开头辅料范围内排除，非辅料不能被误删。
            stoppedPaths.add(node.path());
            continue;
          }
        } else if (ACTION_ROLLUP_TO_PARENT.equals(action)) {
          if (canRollupNode(node, rule, warnings)) {
            consumeRollupNode(node, parent, rule, rollupBuckets, stoppedPaths, consumedLeafPaths, warnings);
            continue;
          }
        } else if (ACTION_STOP_AS_PACKAGE.equals(action) || !ACTION_ADD_PROCESS_FEE.equals(action)) {
          BomCostingRow row = toCostingRow(request, node, rule, rowType(rule, null), markSubtree(rule));
          normalRows.add(row);
          addSourceRefCandidate(node, row.getPath(), sourceRefs);
          stoppedPaths.add(node.path());
          continue;
        } else {
          appendProcessFeeRow(request, node, rule, extraRows, sourceRefs, emittedProcessFeePaths);
          continue;
        }
      }

      if (isOutsourcedNode(node)) {
        // 委外节点本身是结构节点，默认继续下钻；加工费是否输出由下层末级采购件的结算结果决定。
        continue;
      }

      if (isPackageComponentParent(node)) {
        // 包装子件由包装价格逻辑汇总，不进入普通 BOM 结算明细；这里输出包装父件并截断子树。
        BomCostingRow row = toCostingRow(request, node, null, ROW_TYPE_PACKAGE_PARENT, false);
        normalRows.add(row);
        addSourceRefCandidate(node, row.getPath(), sourceRefs);
        stoppedPaths.add(node.path());
        continue;
      }

      if (isManufacturedNode(node) || isNonPackageVirtualNode(node)) {
        // 制造件 / 非包装虚拟件是结构节点，不是最终计价对象；默认继续下钻，不输出自身。
        if (node.leaf()) {
          warnings.add("STRUCTURE_LEAF_NO_CHILD: 结构节点 " + node.materialCode()
              + " path=" + node.path() + " 没有子节点，未输出默认结算行");
        }
        continue;
      }

      if (node.leaf()) {
        BomCostingRow row = toCostingRow(request, node, null, ROW_TYPE_DEFAULT_LEAF, false);
        normalRows.add(row);
        addSourceRefCandidate(node, row.getPath(), sourceRefs);
        appendDirectParentOutsourcedFee(
            request, node, parent, extraRows, sourceRefs, emittedProcessFeePaths);
      }
    }

    appendByproductExtraRows(request, nodes, extraRows, warnings);

    List<BomCostingRow> costingRows = new ArrayList<>();
    List<BomSettlementSubRefCandidate> subRefs = new ArrayList<>();
    materializeRollupBuckets(
        request,
        rollupBuckets,
        nodeByPath,
        stoppedPaths,
        costingRows,
        extraRows,
        subRefs,
        sourceRefs,
        emittedProcessFeePaths,
        warnings);
    costingRows.addAll(extraRows);
    costingRows.addAll(normalRows);

    BomSettlementRowBuildStats stats = new BomSettlementRowBuildStats(
        nodes.size(),
        costingRows.size(),
        subRefs.size(),
        sourceRefs.size(),
        warnings.size(),
        stoppedPaths.size(),
        consumedLeafPaths.size(),
        rollupBuckets.size(),
        extraRows.size());
    return new BomSettlementRowBuildResult(costingRows, subRefs, sourceRefs, warnings, stats);
  }

  private static List<BomSettlementNode> normalizedNodes(
      BomSettlementBuildRequest request, List<String> warnings) {
    if (request == null) {
      return List.of();
    }
    return request.nodes().stream()
        .filter(node -> {
          if (node == null || !StringUtils.hasText(node.path())) {
            warnings.add("NODE_PATH_EMPTY: 跳过 path 为空的 BOM 节点");
            return false;
          }
          if (!isMainManufacturingPurpose(request, node)) {
            return false;
          }
          if (!inEffectiveWindow(request, node)) {
            return false;
          }
          return true;
        })
        .sorted(Comparator.comparing(BomSettlementNode::path))
        .toList();
  }

  private static Map<String, BomSettlementNode> indexByPath(
      List<BomSettlementNode> nodes, List<String> warnings) {
    Map<String, BomSettlementNode> nodeByPath = new LinkedHashMap<>();
    for (BomSettlementNode node : nodes) {
      BomSettlementNode old = nodeByPath.putIfAbsent(node.path(), node);
      if (old != null) {
        warnings.add("NODE_PATH_DUPLICATE: path=" + node.path() + " 重复，保留首个节点");
      }
    }
    return nodeByPath;
  }

  private static Map<String, List<BomSettlementNode>> indexChildren(List<BomSettlementNode> nodes) {
    Map<String, List<BomSettlementNode>> childrenByParentPath = new HashMap<>();
    for (BomSettlementNode node : nodes) {
      String parentPath = parentPathOf(node.path());
      if (parentPath != null) {
        childrenByParentPath.computeIfAbsent(parentPath, ignored -> new ArrayList<>()).add(node);
      }
    }
    return childrenByParentPath;
  }

  private static void validateStructure(
      List<BomSettlementNode> nodes,
      Map<String, BomSettlementNode> nodeByPath,
      Map<String, List<BomSettlementNode>> childrenByParentPath,
      List<String> warnings) {
    for (BomSettlementNode node : nodes) {
      if (Integer.valueOf(0).equals(node.level())) {
        if (childrenByParentPath.getOrDefault(node.path(), List.of()).isEmpty()) {
          warnings.add("TOP_SINGLE_NODE: 顶层产品 " + node.materialCode() + " 只有单节点 BOM");
        }
        continue;
      }
      String parentPath = parentPathOf(node.path());
      if (!StringUtils.hasText(parentPath)) {
        warnings.add("PATH_PARENT_MISSING: 节点 " + node.materialCode()
            + " path=" + node.path() + " 无法解析父 path");
        continue;
      }
      BomSettlementNode parent = nodeByPath.get(parentPath);
      if (parent == null) {
        warnings.add("PATH_CHAIN_BROKEN: 节点 " + node.materialCode()
            + " path=" + node.path() + " 找不到父 path=" + parentPath);
        continue;
      }
      if (StringUtils.hasText(node.parentCode())
          && StringUtils.hasText(parent.materialCode())
          && !node.parentCode().equals(parent.materialCode())) {
        warnings.add("PARENT_CODE_MISMATCH: 节点 " + node.materialCode()
            + " parentCode=" + node.parentCode()
            + " 与父 path 物料 " + parent.materialCode() + " 不一致");
      }
      if (!node.leaf() && childrenByParentPath.getOrDefault(node.path(), List.of()).isEmpty()) {
        warnings.add("NON_LEAF_WITHOUT_CHILD: 非叶子节点 " + node.materialCode()
            + " path=" + node.path() + " 没有子节点");
      }
    }
  }

  private void consumeRollupNode(
      BomSettlementNode node,
      BomSettlementNode parent,
      BomSettlementRule rule,
      Map<String, RollupBucket> rollupBuckets,
      Set<String> stoppedPaths,
      Set<String> consumedLeafPaths,
      List<String> warnings) {
    if (parent == null) {
      warnings.add("ROLLUP_PARENT_MISSING: 规则 id=" + rule.getId()
          + " 命中节点 " + node.materialCode() + " 但找不到直接父节点，跳过上卷");
      return;
    }
    if (stoppedPaths.contains(parent.path()) || isUnderStoppedSubtree(parent.path(), stoppedPaths)) {
      warnings.add("ROLLUP_PARENT_STOPPED: 规则 id=" + rule.getId()
          + " 命中节点 " + node.materialCode() + " 但父节点已被停止，跳过上卷");
      consumedLeafPaths.add(node.path());
      return;
    }

    // 输出父件是结算粒度，不只是页面展示；上卷 bucket 只按父 path 聚合结算行，不把父 path 放入 stoppedPaths；
    // stoppedPaths 表示某个节点的整棵子树已被截断，两者混用会吞掉同父下未命中特殊规则的兄弟叶子。
    rollupBuckets
        .computeIfAbsent(parent.path(), ignored -> new RollupBucket(parent, rule))
        .children.add(node);
    consumedLeafPaths.add(node.path());
    if (!node.leaf()) {
      stoppedPaths.add(node.path());
    }
  }

  private static void materializeRollupBuckets(
      BomSettlementBuildRequest request,
      Map<String, RollupBucket> rollupBuckets,
      Map<String, BomSettlementNode> nodeByPath,
      Set<String> stoppedPaths,
      List<BomCostingRow> costingRows,
      List<BomCostingRow> extraRows,
      List<BomSettlementSubRefCandidate> subRefs,
      List<BomSettlementSourceRefCandidate> sourceRefs,
      Set<String> emittedProcessFeePaths,
      List<String> warnings) {
    for (RollupBucket bucket : rollupBuckets.values()) {
      if (stoppedPaths.contains(bucket.parent.path())
          || isUnderStoppedSubtree(bucket.parent.path(), stoppedPaths)) {
        warnings.add("ROLLUP_BUCKET_PARENT_STOPPED: 父节点 " + bucket.parent.materialCode()
            + " 已被停止，跳过该上卷 bucket");
        continue;
      }
      BomCostingRow parentRow = toCostingRow(
          request,
          bucket.parent,
          bucket.rule,
          rowType(bucket.rule, ROW_TYPE_SPECIAL_ROLLUP_PARENT),
          markSubtree(bucket.rule));
      costingRows.add(parentRow);
      addSourceRefCandidate(bucket.parent, parentRow.getPath(), sourceRefs);
      for (BomSettlementNode child : bucket.children) {
        subRefs.add(new BomSettlementSubRefCandidate(
            parentRow.getPath(), toSubRef(child, bucket.rule)));
      }
      appendRollupParentOutsourcedFee(
          request, bucket.parent, nodeByPath, extraRows, sourceRefs, emittedProcessFeePaths);
    }
  }

  private static BomCostingRow toCostingRow(
      BomSettlementBuildRequest request,
      BomSettlementNode node,
      BomSettlementRule rule,
      String rowType,
      boolean subtreeRequired) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo(request.oaNo());
    row.setTopProductCode(firstText(node.topProductCode(), request.topProductCode()));
    row.setParentCode(Integer.valueOf(0).equals(node.level()) ? null : node.parentCode());
    row.setMaterialCode(node.materialCode());
    row.setLevel(node.level());
    row.setPath(node.path());
    row.setQtyPerParent(node.qtyPerParent());
    row.setQtyPerTop(node.qtyPerTop());
    row.setIsCostingRow(1);
    row.setSubtreeCostRequired(subtreeRequired ? 1 : 0);
    row.setRawHierarchyNodeId(node.sourceNodeId());
    row.setMatchedSettlementRuleId(rule == null ? null : rule.getId());
    row.setSettlementRowType(rowType);
    row.setMaterialName(node.materialName());
    row.setMaterialSpec(node.materialSpec());
    row.setShapeAttr(node.shapeAttr());
    row.setSourceCategory(node.productionCategory());
    row.setCostElementCode(node.costElementCode());
    row.setBomPurpose(firstText(node.bomPurpose(), request.bomPurpose()));
    row.setBomVersion(node.bomVersion());
    row.setU9IsCostFlag(node.u9IsCostFlag());
    row.setEffectiveFrom(node.effectiveFrom());
    row.setEffectiveTo(node.effectiveTo());
    row.setBuildBatchId(request.buildBatchId());
    row.setBuiltAt(request.builtAt() == null ? LocalDateTime.now() : request.builtAt());
    row.setPeriodMonth(periodMonth(request));
    row.setAsOfDate(request.asOfDate());
    row.setRawVersionEffectiveFrom(rawVersionEffectiveFrom(node));
    row.setBusinessUnitType(firstText(request.businessUnitType(), node.businessUnitType()));
    return row;
  }

  private static BomCostingRow toProcessFeeRow(
      BomSettlementBuildRequest request,
      BomSettlementNode node,
      BomSettlementRule rule) {
    BomCostingRow row = toCostingRow(
        request, node, rule, rowType(rule, ROW_TYPE_PROCESS_FEE), false);
    row.setMaterialName(firstText(node.materialName(), node.materialCode()) + "-委外加工费");
    row.setShapeAttr(SHAPE_OUTSOURCED);
    return row;
  }

  private static void appendDirectParentOutsourcedFee(
      BomSettlementBuildRequest request,
      BomSettlementNode leaf,
      BomSettlementNode parent,
      List<BomCostingRow> extraRows,
      List<BomSettlementSourceRefCandidate> sourceRefs,
      Set<String> emittedProcessFeePaths) {
    if (!isTerminalPurchasedNode(leaf) || parent == null || !isOutsourcedNode(parent)) {
      return;
    }
    appendProcessFeeRow(request, parent, null, extraRows, sourceRefs, emittedProcessFeePaths);
  }

  private static void appendRollupParentOutsourcedFee(
      BomSettlementBuildRequest request,
      BomSettlementNode rollupParent,
      Map<String, BomSettlementNode> nodeByPath,
      List<BomCostingRow> extraRows,
      List<BomSettlementSourceRefCandidate> sourceRefs,
      Set<String> emittedProcessFeePaths) {
    if (rollupParent == null || nodeByPath == null) {
      return;
    }
    BomSettlementNode parent = nodeByPath.get(parentPathOf(rollupParent.path()));
    if (parent == null || !isOutsourcedNode(parent)) {
      return;
    }
    appendProcessFeeRow(request, parent, null, extraRows, sourceRefs, emittedProcessFeePaths);
  }

  private static void appendProcessFeeRow(
      BomSettlementBuildRequest request,
      BomSettlementNode node,
      BomSettlementRule rule,
      List<BomCostingRow> extraRows,
      List<BomSettlementSourceRefCandidate> sourceRefs,
      Set<String> emittedProcessFeePaths) {
    if (node == null || !StringUtils.hasText(node.path()) || !emittedProcessFeePaths.add(node.path())) {
      return;
    }
    BomCostingRow row = toProcessFeeRow(request, node, rule);
    extraRows.add(row);
    addSourceRefCandidate(node, row.getPath(), sourceRefs);
  }

  private void appendByproductExtraRows(
      BomSettlementBuildRequest request,
      List<BomSettlementNode> nodes,
      List<BomCostingRow> extraRows,
      List<String> warnings) {
    if (request.byproducts().isEmpty() || request.byproductRules().isEmpty()) {
      return;
    }
    Map<String, List<BomSettlementNode>> manufacturedNodesByMaterialCode = new LinkedHashMap<>();
    for (BomSettlementNode node : nodes) {
      if (isManufacturedNode(node)) {
        manufacturedNodesByMaterialCode
            .computeIfAbsent(node.materialCode(), ignored -> new ArrayList<>())
            .add(node);
      }
    }
    for (BomSettlementByproduct byproduct : request.byproducts()) {
      if (byproduct == null || !inByproductEffectiveWindow(request, byproduct)) {
        continue;
      }
      List<BomSettlementNode> parentNodes = manufacturedNodesByMaterialCode
          .getOrDefault(byproduct.parentMaterialCode(), List.of());
      if (parentNodes.isEmpty()) {
        warnings.add("BYPRODUCT_PARENT_NOT_FOUND: 副产品 "
            + byproduct.byproductMaterialCode()
            + " 找不到制造件母项 " + byproduct.parentMaterialCode());
        continue;
      }
      for (BomSettlementNode parent : parentNodes) {
        appendOneByproductExtraRow(request, nodes, parent, byproduct, extraRows);
      }
    }
  }

  private void appendOneByproductExtraRow(
      BomSettlementBuildRequest request,
      List<BomSettlementNode> nodes,
      BomSettlementNode parent,
      BomSettlementByproduct byproduct,
      List<BomCostingRow> extraRows) {
    Set<String> lowerRawMaterialCodes = lowerRawMaterialCodes(parent, nodes);
    if (hasScrapRefMatch(request, byproduct, lowerRawMaterialCodes)) {
      return;
    }
    BomRuleNodeContext context = byproductRuleContext(request, parent, byproduct);
    Optional<BomByproductCostRule> hit = byproductRuleMatcher.match(
        context,
        ADD_CONDITION_NO_SCRAP_REF_MATCH,
        firstText(byproduct.bomPurpose(), requestedBomPurpose(request, parent)),
        request.asOfDate(),
        request.byproductRules());
    hit.ifPresent(rule -> extraRows.add(toByproductExtraRow(request, parent, byproduct, rule)));
  }

  private static Set<String> lowerRawMaterialCodes(
      BomSettlementNode parent,
      List<BomSettlementNode> nodes) {
    Set<String> materialCodes = new LinkedHashSet<>();
    for (BomSettlementNode node : nodes) {
      if (!node.path().equals(parent.path())
          && node.path().startsWith(parent.path())
          && isTerminalPurchasedNode(node)
          && StringUtils.hasText(node.materialCode())) {
        materialCodes.add(node.materialCode());
      }
    }
    return materialCodes;
  }

  private static boolean hasScrapRefMatch(
      BomSettlementBuildRequest request,
      BomSettlementByproduct byproduct,
      Set<String> lowerRawMaterialCodes) {
    if (lowerRawMaterialCodes.isEmpty()) {
      return false;
    }
    LocalDate asOfDate = request.asOfDate() == null ? LocalDate.now() : request.asOfDate();
    for (BomSettlementScrapRef ref : request.scrapRefs()) {
      if (ref == null) {
        continue;
      }
      if (lowerRawMaterialCodes.contains(ref.materialCode())
          && byproduct.byproductMaterialCode().equals(ref.scrapCode())
          && inEffectiveWindow(asOfDate, ref.effectiveFrom(), ref.effectiveTo())
          && scopeMatches(ref.businessUnitType(), firstText(request.businessUnitType(), byproduct.businessUnitType()))) {
        return true;
      }
    }
    return false;
  }

  private static BomRuleNodeContext byproductRuleContext(
      BomSettlementBuildRequest request,
      BomSettlementNode parent,
      BomSettlementByproduct byproduct) {
    return new BomRuleNodeContext(
        byproduct.byproductMaterialCode(),
        byproduct.byproductMaterialName(),
        null,
        null,
        null,
        parent.shapeAttr(),
        parent.costElementCode(),
        parent.productionCategory(),
        firstText(request.businessUnitType(), byproduct.businessUnitType()),
        firstText(byproduct.bomPurpose(), requestedBomPurpose(request, parent)));
  }

  private static BomCostingRow toByproductExtraRow(
      BomSettlementBuildRequest request,
      BomSettlementNode parent,
      BomSettlementByproduct byproduct,
      BomByproductCostRule rule) {
    BomCostingRow row = new BomCostingRow();
    BigDecimal qtyPerParent = byproduct.outputQty() == null ? BigDecimal.ONE : byproduct.outputQty();
    BigDecimal parentQtyPerTop = parent.qtyPerTop() == null ? BigDecimal.ONE : parent.qtyPerTop();
    row.setOaNo(request.oaNo());
    row.setTopProductCode(firstText(parent.topProductCode(), request.topProductCode()));
    row.setParentCode(parent.materialCode());
    row.setMaterialCode(byproduct.byproductMaterialCode());
    row.setLevel(parent.level() == null ? null : parent.level() + 1);
    row.setPath(parent.path() + "__BYPRODUCT__/" + byproduct.byproductMaterialCode() + "/");
    row.setQtyPerParent(qtyPerParent);
    row.setQtyPerTop(parentQtyPerTop.multiply(qtyPerParent));
    row.setIsCostingRow(1);
    row.setSubtreeCostRequired(0);
    row.setRawHierarchyNodeId(null);
    row.setMatchedSettlementRuleId(rule.getId());
    row.setSettlementRowType(firstText(rule.getSettlementRowType(), ROW_TYPE_BYPRODUCT_EXTRA));
    row.setMaterialName(byproduct.byproductMaterialName());
    row.setMaterialSpec(byproduct.byproductMaterialSpec());
    row.setShapeAttr("副产品");
    row.setSourceCategory(parent.productionCategory());
    row.setCostElementCode(parent.costElementCode());
    row.setBomPurpose(firstText(byproduct.bomPurpose(), requestedBomPurpose(request, parent)));
    row.setBomVersion(firstText(byproduct.versionNo(), parent.bomVersion()));
    row.setU9IsCostFlag(1);
    row.setEffectiveFrom(byproduct.effectiveFrom());
    row.setEffectiveTo(byproduct.effectiveTo());
    row.setBuildBatchId(request.buildBatchId());
    row.setBuiltAt(request.builtAt() == null ? LocalDateTime.now() : request.builtAt());
    row.setPeriodMonth(periodMonth(request));
    row.setAsOfDate(request.asOfDate());
    row.setRawVersionEffectiveFrom(rawVersionEffectiveFrom(parent));
    row.setBusinessUnitType(firstText(request.businessUnitType(), byproduct.businessUnitType()));
    return row;
  }

  private static BomCostingRowSubRef toSubRef(BomSettlementNode child, BomSettlementRule rule) {
    BomCostingRowSubRef ref = new BomCostingRowSubRef();
    ref.setRefType(firstText(rule.getSubRefType(), REF_TYPE_SPECIAL_ROLLUP_CHILD));
    ref.setMatchedSettlementRuleId(rule.getId());
    ref.setSubMaterialCode(child.materialCode());
    ref.setSubMaterialName(child.materialName());
    ref.setSubMaterialCategory(child.mainCategoryName());
    ref.setSubQtyPerParent(child.qtyPerParent());
    ref.setSubQtyPerTop(child.qtyPerTop());
    ref.setSubRawHierarchyId(child.sourceNodeId());
    ref.setSubPath(child.path());
    ref.setBusinessUnitType(child.businessUnitType());
    return ref;
  }

  private static void addSourceRefCandidate(
      BomSettlementNode node,
      String costingRowPath,
      List<BomSettlementSourceRefCandidate> sourceRefs) {
    BomSettlementSourceRef source = node.sourceRef();
    if (source == null) {
      return;
    }
    BomCostingRowSourceRef ref = new BomCostingRowSourceRef();
    ref.setOaNo(source.oaNo());
    ref.setOaFormItemId(source.oaFormItemId());
    ref.setQuoteProductCode(source.quoteProductCode());
    ref.setSourcePartType(source.sourcePartType());
    ref.setSourceRawHierarchyId(source.sourceRawHierarchyId());
    ref.setSourceTaskId(source.sourceTaskId());
    ref.setPreparationId(source.preparationId());
    ref.setSupplementVersionId(source.supplementVersionId());
    ref.setSupplementDetailId(source.supplementDetailId());
    ref.setPackageReferenceId(source.packageReferenceId());
    ref.setPackageReferenceDetailId(source.packageReferenceDetailId());
    ref.setReferenceFinishedCode(source.referenceFinishedCode());
    ref.setSourceTopProductCode(source.sourceTopProductCode());
    ref.setSourceSnapshotId(source.sourceSnapshotId());
    ref.setSourceSnapshotDetailId(source.sourceSnapshotDetailId());
    ref.setSourceU9BomId(source.sourceU9BomId());
    ref.setSourcePath(source.sourcePath());
    sourceRefs.add(new BomSettlementSourceRefCandidate(costingRowPath, ref));
  }

  private static BomRuleNodeContext toRuleContext(
      BomSettlementNode node, BomSettlementBuildRequest request) {
    return new BomRuleNodeContext(
        node.materialCode(),
        node.materialName(),
        node.materialCategoryCode(),
        node.mainCategoryName(),
        node.purchaseCategory(),
        node.shapeAttr(),
        node.costElementCode(),
        node.productionCategory(),
        firstText(request.businessUnitType(), node.businessUnitType()),
        firstText(node.bomPurpose(), request.bomPurpose()));
  }

  private static boolean isUnderStoppedSubtree(String path, Set<String> stoppedPaths) {
    if (!StringUtils.hasText(path)) {
      return false;
    }
    for (String stoppedPath : stoppedPaths) {
      if (!path.equals(stoppedPath) && path.startsWith(stoppedPath)) {
        return true;
      }
    }
    return false;
  }

  private static String parentPathOf(String path) {
    if (!StringUtils.hasText(path)) {
      return null;
    }
    String normalized = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
    int lastSlash = normalized.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    return normalized.substring(0, lastSlash + 1);
  }

  private static String rowType(BomSettlementRule rule, String fallback) {
    if (rule == null) {
      return fallback == null ? ROW_TYPE_DEFAULT_LEAF : fallback;
    }
    return firstText(rule.getSettlementRowType(), fallback == null ? ROW_TYPE_DEFAULT_LEAF : fallback);
  }

  private static boolean markSubtree(BomSettlementRule rule) {
    return rule != null && Integer.valueOf(1).equals(rule.getMarkSubtreeCostRequired());
  }

  private static String requestedBomPurpose(BomSettlementBuildRequest request, BomSettlementNode node) {
    return firstText(request.bomPurpose(), node.bomPurpose());
  }

  private static boolean isMainManufacturingPurpose(
      BomSettlementBuildRequest request, BomSettlementNode node) {
    String nodePurpose = node.bomPurpose();
    if (StringUtils.hasText(nodePurpose)) {
      return BOM_PURPOSE_MAIN_MANUFACTURING.equals(nodePurpose);
    }
    String requestPurpose = request.bomPurpose();
    return !StringUtils.hasText(requestPurpose)
        || BOM_PURPOSE_MAIN_MANUFACTURING.equals(requestPurpose);
  }

  private static boolean inEffectiveWindow(BomSettlementBuildRequest request, BomSettlementNode node) {
    LocalDate asOfDate = request.asOfDate() == null ? LocalDate.now() : request.asOfDate();
    return inEffectiveWindow(asOfDate, node.effectiveFrom(), node.effectiveTo());
  }

  private static boolean inByproductEffectiveWindow(
      BomSettlementBuildRequest request, BomSettlementByproduct byproduct) {
    LocalDate asOfDate = request.asOfDate() == null ? LocalDate.now() : request.asOfDate();
    return inEffectiveWindow(asOfDate, byproduct.effectiveFrom(), byproduct.effectiveTo());
  }

  private static boolean inEffectiveWindow(LocalDate asOfDate, LocalDate effectiveFrom, LocalDate effectiveTo) {
    if (effectiveFrom != null && asOfDate.isBefore(effectiveFrom)) {
      return false;
    }
    return effectiveTo == null || !asOfDate.isAfter(effectiveTo);
  }

  private static boolean isPackageComponentParent(BomSettlementNode node) {
    return isVirtualShape(node)
        && StringUtils.hasText(node.materialCategoryCode())
        && node.materialCategoryCode().startsWith(CATEGORY_PACKAGE_COMPONENT_PREFIX);
  }

  private static boolean isNonPackageVirtualNode(BomSettlementNode node) {
    return isVirtualShape(node) && !isPackageComponentParent(node);
  }

  private static boolean isManufacturedNode(BomSettlementNode node) {
    return SHAPE_MANUFACTURED.equals(node.shapeAttr())
        || SHAPE_MANUFACTURED.equals(node.productionCategory());
  }

  private static boolean isOutsourcedNode(BomSettlementNode node) {
    return SHAPE_OUTSOURCED.equals(node.shapeAttr())
        || SHAPE_OUTSOURCED.equals(node.productionCategory())
        || SHAPE_OUTSOURCED_SHORT.equals(node.shapeAttr())
        || SHAPE_OUTSOURCED_SHORT.equals(node.productionCategory());
  }

  private static boolean isVirtualShape(BomSettlementNode node) {
    return SHAPE_VIRTUAL.equals(node.shapeAttr());
  }

  private static boolean canRollupNode(
      BomSettlementNode node, BomSettlementRule rule, List<String> warnings) {
    if (!RULE_CATEGORY_SPECIAL_PURCHASE_ROLLUP.equals(rule.getRuleCategory())) {
      return true;
    }
    if (isTerminalPurchasedNode(node)) {
      return true;
    }
    warnings.add("SPECIAL_PURCHASE_ROLLUP_NOT_PURCHASE_LEAF: 规则 id=" + rule.getId()
        + " 命中非末级采购件 " + node.materialCode() + " path=" + node.path() + "，跳过上卷");
    return false;
  }

  private static boolean shouldExcludeNode(BomSettlementNode node, BomSettlementRule rule) {
    if (!RULE_CATEGORY_AUXILIARY_EXCLUDE.equals(rule.getRuleCategory())) {
      return true;
    }
    return isTerminalPurchasedNode(node)
        && StringUtils.hasText(node.materialCategoryCode())
        && node.materialCategoryCode().startsWith(CATEGORY_AUXILIARY_PREFIX);
  }

  private static boolean isTerminalPurchasedNode(BomSettlementNode node) {
    return node.leaf()
        && (SHAPE_PURCHASED.equals(node.shapeAttr())
            || SHAPE_PURCHASED.equals(node.productionCategory()));
  }

  private static String periodMonth(BomSettlementBuildRequest request) {
    if (StringUtils.hasText(request.periodMonth())) {
      return request.periodMonth();
    }
    LocalDate asOfDate = request.asOfDate() == null ? LocalDate.now() : request.asOfDate();
    return YearMonth.from(asOfDate).toString();
  }

  private static LocalDate rawVersionEffectiveFrom(BomSettlementNode node) {
    if (node.rawVersionEffectiveFrom() != null) {
      return node.rawVersionEffectiveFrom();
    }
    return node.effectiveFrom() == null ? LocalDate.of(1970, 1, 1) : node.effectiveFrom();
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim().toUpperCase();
  }

  private static String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first;
    }
    return StringUtils.hasText(second) ? second : null;
  }

  private static boolean scopeMatches(String ruleScope, String requestedScope) {
    if (!StringUtils.hasText(ruleScope)) {
      return true;
    }
    return ruleScope.equals(requestedScope);
  }

  private static class RollupBucket {
    private final BomSettlementNode parent;
    private final BomSettlementRule rule;
    private final List<BomSettlementNode> children = new ArrayList<>();

    private RollupBucket(BomSettlementNode parent, BomSettlementRule rule) {
      this.parent = parent;
      this.rule = rule;
    }
  }
}
