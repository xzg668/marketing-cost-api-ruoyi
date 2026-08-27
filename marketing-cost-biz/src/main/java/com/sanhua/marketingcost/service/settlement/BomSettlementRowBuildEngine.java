package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSourceRef;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.BomByproductCostRule;
import com.sanhua.marketingcost.entity.BomSettlementRule;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.rule.BomRuleNodeContext;
import com.sanhua.marketingcost.service.rule.BomByproductCostRuleMatcher;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributeResolver;
import com.sanhua.marketingcost.service.rule.BomRuleMaterialAttributes;
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
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final String SHAPE_OUTSOURCED = "委外加工件";
  private static final String CATEGORY_PACKAGE_COMPONENT_PREFIX = "15155";
  private final BomSettlementRuleMatcher ruleMatcher;
  private final BomByproductCostRuleMatcher byproductRuleMatcher;
  private final BomRuleMaterialAttributeResolver materialAttributeResolver;

  @Autowired
  public BomSettlementRowBuildEngine(
      BomSettlementRuleMatcher ruleMatcher,
      BomByproductCostRuleMatcher byproductRuleMatcher,
      BomRuleMaterialAttributeResolver materialAttributeResolver) {
    this.ruleMatcher = ruleMatcher;
    this.byproductRuleMatcher = byproductRuleMatcher;
    this.materialAttributeResolver = materialAttributeResolver;
  }

  /** 纯内存测试入口；生产环境使用注入 U9 原始料品档案解析器的构造方法。 */
  public BomSettlementRowBuildEngine(
      BomSettlementRuleMatcher ruleMatcher,
      BomByproductCostRuleMatcher byproductRuleMatcher) {
    this(ruleMatcher, byproductRuleMatcher, (ignoredCodes, ignoredOrganization) -> Map.of());
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
    Map<String, BomRuleMaterialAttributes> materialAttributes =
        resolveMaterialAttributes(nodes);
    Set<String> byproductParentMaterialCodes = effectiveByproductParentMaterialCodes(request);
    Map<String, BomSettlementNode> nodeByPath = indexByPath(nodes, warnings);
    Map<String, List<BomSettlementNode>> childrenByParentPath = indexChildren(nodes);
    validateStructure(nodes, nodeByPath, childrenByParentPath, warnings);
    List<BomSettlementRule> excludeRules = request.settlementRules().stream()
        .filter(rule -> rule != null)
        .filter(rule -> ACTION_EXCLUDE.equals(normalize(rule.getSettlementAction())))
        .toList();
    List<BomSettlementRule> nonExcludeRules = request.settlementRules().stream()
        .filter(rule -> rule != null)
        .filter(rule -> !ACTION_EXCLUDE.equals(normalize(rule.getSettlementAction())))
        .toList();

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
      BomRuleNodeContext nodeContext = toRuleContext(
          node,
          request,
          materialAttributes,
          byproductParentMaterialCodes.contains(trimToNull(node.materialCode())));
      BomRuleNodeContext parentContext =
          parent == null
              ? null
              : toRuleContext(
                  parent,
                  request,
                  materialAttributes,
                  byproductParentMaterialCodes.contains(trimToNull(parent.materialCode())));
      List<BomRuleNodeContext> childContexts = children.stream()
          .map(child -> toRuleContext(
              child,
              request,
              materialAttributes,
              byproductParentMaterialCodes.contains(trimToNull(child.materialCode()))))
          .toList();
      Optional<BomSettlementRule> exclusionHit = ruleMatcher.match(
          nodeContext,
          parentContext,
          childContexts,
          requestedBomPurpose(request, node),
          request.asOfDate(),
          excludeRules);

      // 排除优先于上卷、包装停止和附加行，避免同一节点同时命中时被低序号规则抢先输出。
      if (exclusionHit.isPresent() && shouldExcludeNode(node, exclusionHit.get())) {
        stoppedPaths.add(node.path());
        continue;
      }

      Optional<BomSettlementRule> hit = ruleMatcher.match(
          nodeContext,
          parentContext,
          childContexts,
          requestedBomPurpose(request, node),
          request.asOfDate(),
          nonExcludeRules);

      // 排除完成后，再做上卷、包装/停止边界、额外附加行，最后才落到默认叶子。
      if (hit.isPresent()) {
        BomSettlementRule rule = hit.get();
        String action = normalize(rule.getSettlementAction());

        if (ACTION_ROLLUP_TO_PARENT.equals(action)) {
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
    List<BomCostingRow> byproductAnchorRows = new ArrayList<>(costingRows);
    byproductAnchorRows.addAll(normalRows);
    appendByproductExtraRows(
        request,
        nodes,
        byproductAnchorRows,
        nodeByPath,
        childrenByParentPath,
        rollupBuckets.keySet(),
        extraRows,
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
    row.setPriceOrgCode(node.priceOrgCode());
    row.setMaterialOrganizationCode(node.materialOrganizationCode());
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
      List<BomCostingRow> anchorRows,
      Map<String, BomSettlementNode> nodeByPath,
      Map<String, List<BomSettlementNode>> childrenByParentPath,
      Set<String> rollupParentPaths,
      List<BomCostingRow> extraRows,
      List<String> warnings) {
    if (request.byproducts().isEmpty() || request.byproductRules().isEmpty()) {
      return;
    }
    Map<String, List<BomSettlementNode>> allManufacturedNodesByMaterialCode = new LinkedHashMap<>();
    for (BomSettlementNode node : nodes) {
      if (isManufacturedNode(node)) {
        allManufacturedNodesByMaterialCode
            .computeIfAbsent(node.materialCode(), ignored -> new ArrayList<>())
            .add(node);
      }
    }
    Map<String, List<BomSettlementNode>> byproductCandidateNodesByMaterialCode =
        byproductCandidateNodes(anchorRows, nodeByPath, rollupParentPaths);
    for (BomSettlementByproduct byproduct : request.byproducts()) {
      if (byproduct == null || !inByproductEffectiveWindow(request, byproduct)) {
        continue;
      }
      List<BomSettlementNode> parentNodes = byproductCandidateNodesByMaterialCode
          .getOrDefault(byproduct.parentMaterialCode(), List.of());
      if (parentNodes.isEmpty()) {
        if (!allManufacturedNodesByMaterialCode.containsKey(byproduct.parentMaterialCode())) {
          warnings.add("BYPRODUCT_PARENT_NOT_FOUND: 副产品 "
              + byproduct.byproductMaterialCode()
              + " 找不到制造件母项 " + byproduct.parentMaterialCode());
        }
        continue;
      }
      for (BomSettlementNode parent : parentNodes) {
        appendOneByproductExtraRow(
            request, childrenByParentPath, parent, byproduct, extraRows);
      }
    }
  }

  private static Map<String, List<BomSettlementNode>> byproductCandidateNodes(
      List<BomCostingRow> anchorRows,
      Map<String, BomSettlementNode> nodeByPath,
      Set<String> rollupParentPaths) {
    Map<String, List<BomSettlementNode>> candidates = new LinkedHashMap<>();
    Set<String> candidatePaths = new LinkedHashSet<>();
    if (anchorRows == null || nodeByPath == null || nodeByPath.isEmpty()) {
      return candidates;
    }
    for (BomCostingRow row : anchorRows) {
      if (row == null || !StringUtils.hasText(row.getPath())) {
        continue;
      }
      String path = row.getPath();
      boolean skipCurrent = ROW_TYPE_SPECIAL_ROLLUP_PARENT.equals(row.getSettlementRowType());
      while (StringUtils.hasText(path)) {
        BomSettlementNode node = nodeByPath.get(path);
        if (!skipCurrent) {
          addByproductCandidate(candidates, candidatePaths, node, rollupParentPaths);
        }
        skipCurrent = false;
        path = parentPathOf(path);
      }
    }
    return candidates;
  }

  private static void addByproductCandidate(
      Map<String, List<BomSettlementNode>> candidates,
      Set<String> candidatePaths,
      BomSettlementNode node,
      Set<String> rollupParentPaths) {
    if (node == null || !isManufacturedNode(node) || !StringUtils.hasText(node.path())) {
      return;
    }
    if (rollupParentPaths != null && rollupParentPaths.contains(node.path())) {
      return;
    }
    if (!candidatePaths.add(node.path())) {
      return;
    }
    candidates.computeIfAbsent(node.materialCode(), ignored -> new ArrayList<>()).add(node);
  }

  private void appendOneByproductExtraRow(
      BomSettlementBuildRequest request,
      Map<String, List<BomSettlementNode>> childrenByParentPath,
      BomSettlementNode parent,
      BomSettlementByproduct byproduct,
      List<BomCostingRow> extraRows) {
    Set<String> lowerRawMaterialCodes = sameProcessingLayerRawMaterialCodes(
        parent, childrenByParentPath);
    if (hasEffectiveScrapMapping(request, byproduct, lowerRawMaterialCodes)) {
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

  /**
   * 查找当前制造件同一加工层的末级采购件。
   *
   * <p>采购件废料映射只负责它最近的加工父件成本，不能穿透中间制造件继续抑制更上层
   * 制造件自己的 U9 副产品。因此向下遍历遇到新的制造/委外加工节点时立即停止该分支；
   * 虚拟结构节点仍继续下钻。
   */
  private static Set<String> sameProcessingLayerRawMaterialCodes(
      BomSettlementNode parent,
      Map<String, List<BomSettlementNode>> childrenByParentPath) {
    Set<String> materialCodes = new LinkedHashSet<>();
    if (parent == null || !StringUtils.hasText(parent.path()) || childrenByParentPath == null) {
      return materialCodes;
    }
    List<BomSettlementNode> pending = new ArrayList<>(
        childrenByParentPath.getOrDefault(parent.path(), List.of()));
    for (int index = 0; index < pending.size(); index++) {
      BomSettlementNode node = pending.get(index);
      if (node == null || !StringUtils.hasText(node.path())) {
        continue;
      }
      if (isNestedProcessingBoundary(node)) {
        continue;
      }
      if (isTerminalPurchasedNode(node) && StringUtils.hasText(node.materialCode())) {
        materialCodes.add(node.materialCode());
        continue;
      }
      pending.addAll(childrenByParentPath.getOrDefault(node.path(), List.of()));
    }
    return materialCodes;
  }

  private static boolean isNestedProcessingBoundary(BomSettlementNode node) {
    return !isVirtualShape(node) && (isManufacturedNode(node) || isOutsourcedNode(node));
  }

  /**
   * 当前加工层只要存在有效的采购件废料映射，就说明父件已经按“采购件 + 废料”计算。
   *
   * <p>这里不能再要求映射的废料料号与 U9 副产品料号相同：两者是不同来源的业务数据，
   * 料号不同也不能把直接加工父件自己的 U9 副产品重复输出。制造边界之上的父件不会进入
   * {@code lowerRawMaterialCodes}，因此其 U9 副产品仍会逐级独立输出。
   */
  private static boolean hasEffectiveScrapMapping(
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
          && StringUtils.hasText(ref.scrapCode())
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
    BigDecimal qtyPerParent = negativeQty(byproduct.outputQty());
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
    row.setMaterialName(byproductDisplayName(parent));
    row.setMaterialSpec(firstText(parent.materialSpec(), byproduct.byproductMaterialSpec()));
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
    row.setPriceOrgCode(parent.priceOrgCode());
    row.setMaterialOrganizationCode(parent.materialOrganizationCode());
    row.setBusinessUnitType(firstText(request.businessUnitType(), byproduct.businessUnitType()));
    return row;
  }

  private static BigDecimal negativeQty(BigDecimal outputQty) {
    BigDecimal qty = outputQty == null ? BigDecimal.ONE : outputQty;
    return qty.signum() > 0 ? qty.negate() : qty;
  }

  private static String byproductDisplayName(BomSettlementNode parent) {
    String parentName = firstText(parent.materialName(), parent.materialCode());
    String parentSpec = parent.materialSpec();
    if (StringUtils.hasText(parentSpec)) {
      return parentName + "/" + parentSpec.trim() + " 废料";
    }
    return parentName + " 废料";
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
      BomSettlementNode node,
      BomSettlementBuildRequest request,
      Map<String, BomRuleMaterialAttributes> materialAttributes,
      boolean hasByproduct) {
    BomRuleMaterialAttributes attributes =
        materialAttributes.get(trimToNull(node.materialCode()));
    return new BomRuleNodeContext(
        node.materialCode(),
        node.materialName(),
        node.materialCategoryCode(),
        attributes == null ? null : attributes.mainCategoryCode(),
        node.mainCategoryName(),
        attributes == null ? null : attributes.purchaseCategory(),
        node.shapeAttr(),
        node.costElementCode(),
        node.productionCategory(),
        firstText(request.businessUnitType(), node.businessUnitType()),
        firstText(node.bomPurpose(), request.bomPurpose()),
        hasByproduct);
  }

  private static Set<String> effectiveByproductParentMaterialCodes(
      BomSettlementBuildRequest request) {
    if (request == null || request.byproducts().isEmpty()) {
      return Set.of();
    }
    Set<String> result = new LinkedHashSet<>();
    for (BomSettlementByproduct byproduct : request.byproducts()) {
      if (byproduct == null
          || !inByproductEffectiveWindow(request, byproduct)
          || !scopeMatches(byproduct.bomPurpose(), request.bomPurpose())
          || !scopeMatches(byproduct.businessUnitType(), request.businessUnitType())) {
        continue;
      }
      String parentMaterialCode = trimToNull(byproduct.parentMaterialCode());
      if (parentMaterialCode != null) {
        result.add(parentMaterialCode);
      }
    }
    return Set.copyOf(result);
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
    return matchesShape(node.shapeAttr(), QuoteMaterialShape.MANUFACTURE)
        || matchesShape(node.productionCategory(), QuoteMaterialShape.MANUFACTURE);
  }

  private static boolean isOutsourcedNode(BomSettlementNode node) {
    return matchesShape(node.shapeAttr(), QuoteMaterialShape.OUTSOURCE)
        || matchesShape(node.productionCategory(), QuoteMaterialShape.OUTSOURCE);
  }

  private static boolean isVirtualShape(BomSettlementNode node) {
    return matchesShape(node.shapeAttr(), QuoteMaterialShape.VIRTUAL);
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
    return isTerminalPurchasedNode(node);
  }

  private Map<String, BomRuleMaterialAttributes> resolveMaterialAttributes(
      List<BomSettlementNode> nodes) {
    Map<String, Set<String>> materialCodesByOrganization = new LinkedHashMap<>();
    for (BomSettlementNode node : nodes) {
      String materialCode = trimToNull(node.materialCode());
      String organizationCode = materialOrganizationCode(node);
      if (materialCode != null && organizationCode != null) {
        materialCodesByOrganization
            .computeIfAbsent(organizationCode, ignored -> new LinkedHashSet<>())
            .add(materialCode);
      }
    }
    Map<String, BomRuleMaterialAttributes> result = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> entry : materialCodesByOrganization.entrySet()) {
      Map<String, BomRuleMaterialAttributes> resolved =
          materialAttributeResolver.resolve(entry.getValue(), entry.getKey());
      if (resolved != null) {
        result.putAll(resolved);
      }
    }
    return Map.copyOf(result);
  }

  private static String materialOrganizationCode(BomSettlementNode node) {
    String organizationCode = trimToNull(node.materialOrganizationCode());
    if (organizationCode != null) {
      return MaterialOrganization.normalize(organizationCode);
    }
    String priceOrgCode = trimToNull(node.priceOrgCode());
    return priceOrgCode == null
        ? null
        : MaterialOrganization.fromPriceOrgCode(priceOrgCode).getCode();
  }

  private static boolean isTerminalPurchasedNode(BomSettlementNode node) {
    return node.leaf()
        && (matchesShape(node.shapeAttr(), QuoteMaterialShape.PURCHASE)
            || matchesShape(node.productionCategory(), QuoteMaterialShape.PURCHASE));
  }

  private static boolean matchesShape(String value, QuoteMaterialShape expected) {
    if (!StringUtils.hasText(value) || expected == null) {
      return false;
    }
    try {
      return QuoteMaterialShape.fromU9(value) == expected;
    } catch (IllegalArgumentException ignored) {
      return false;
    }
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

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
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
