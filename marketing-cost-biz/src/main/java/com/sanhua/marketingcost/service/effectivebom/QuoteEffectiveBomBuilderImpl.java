package com.sanhua.marketingcost.service.effectivebom;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneRequest;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 最终有效 BOM 的纯树转换实现。
 *
 * <p>固定顺序：替代分支裁剪、结构校验、形态处理、数量和路径重算。不访问数据库，也不修改输入节点。
 */
@Component
public final class QuoteEffectiveBomBuilderImpl
    implements QuoteEffectiveBomBuilder {

  private static final Comparator<BomRawHierarchy> NODE_ORDER =
      Comparator.<BomRawHierarchy>comparingInt(
              row -> row.getSortSeq() == null ? Integer.MAX_VALUE : row.getSortSeq())
          .thenComparing(
              row -> text(row.getMaterialCode()), Comparator.naturalOrder())
          .thenComparing(
              row -> text(row.getSourceLineKey()), Comparator.naturalOrder())
          .thenComparing(
              BomRawHierarchy::getId,
              Comparator.nullsLast(Comparator.naturalOrder()));

  private final BomAlternativeBranchPruner alternativePruner;
  private final EffectiveBomPolicyActionResolver policyActionResolver;

  public QuoteEffectiveBomBuilderImpl(
      BomAlternativeBranchPruner alternativePruner,
      EffectiveBomPolicyActionResolver policyActionResolver) {
    this.alternativePruner = alternativePruner;
    this.policyActionResolver = policyActionResolver;
  }

  @Override
  public EffectiveBomBuildResult build(EffectiveBomBuildRequest request) {
    if (request == null) {
      return failed("REQUEST_NULL", null, null, "最终有效BOM构建请求不能为空");
    }
    if (request.nodes().isEmpty()) {
      return failed("BOM_EMPTY", null, null, "原始候选BOM为空");
    }

    SelectionContext selectionContext = resolveSelections(request);
    if (selectionContext.failure() != null) {
      return result(
          List.of(), List.of(), List.of(selectionContext.failure()), List.of());
    }

    BomAlternativePruneResult pruned;
    try {
      pruned =
          alternativePruner.prune(
              new BomAlternativePruneRequest(
                  request.nodes(),
                  request.alternativeGroups(),
                  selectionContext.selections()));
    } catch (QuoteBomAlternativeSelectionException ex) {
      return failed(ex.getCode(), null, null, ex.getMessage());
    } catch (RuntimeException ex) {
      return failed(
          "ALTERNATIVE_PRUNE_FAILED",
          null,
          null,
          "标准/替代分支裁剪失败: " + ex.getMessage());
    }

    List<EffectiveBomExclusion> exclusions =
        alternativeExclusions(request.nodes(), pruned.nodes());
    Structure structure = indexStructure(pruned.nodes());
    if (!structure.issues().isEmpty()) {
      return result(
          List.of(), exclusions, structure.issues(), pruned.warnings());
    }

    Map<String, EffectiveBomShapeDecision> decisions =
        normalizeDecisions(request.shapeDecisionByMaterialCode());
    BuildState state =
        new BuildState(
            new ArrayList<>(exclusions),
            new ArrayList<>(),
            new ArrayList<>(),
            new LinkedHashSet<>());
    visit(
        structure.root(),
        null,
        BigDecimal.ONE,
        0,
        new LinkedHashSet<>(),
        structure,
        decisions,
        selectionContext.selectionSourceByGroup(),
        request.maxDepth(),
        state);

    for (String path : structure.nodeByPath().keySet()) {
      if (!state.handledPaths().contains(path)) {
        BomRawHierarchy row = structure.nodeByPath().get(path);
        state.issues().add(
            issue(
                "UNREACHABLE_NODE",
                row,
                "节点未从唯一顶层根节点到达，不能进入最终有效BOM"));
      }
    }
    return result(
        state.nodes(),
        state.exclusions(),
        state.issues(),
        pruned.warnings());
  }

  private void visit(
      BomRawHierarchy row,
      EffectiveBomNodeDraft parent,
      BigDecimal parentQtyPerTop,
      int depth,
      LinkedHashSet<String> ancestorMaterials,
      Structure structure,
      Map<String, EffectiveBomShapeDecision> decisions,
      Map<String, String> selectionSourceByGroup,
      int maxDepth,
      BuildState state) {
    String path = normalizePath(row.getPath());
    if (depth > maxDepth) {
      state.issues().add(
          issue(
              "MAX_DEPTH_EXCEEDED",
              row,
              "BOM层级超过保护上限" + maxDepth));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    }
    String materialCode = trimToNull(row.getMaterialCode());
    if (materialCode == null) {
      state.issues().add(issue("MATERIAL_CODE_MISSING", row, "节点料号为空"));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    }
    if (ancestorMaterials.contains(materialCode)) {
      state.issues().add(
          issue(
              "BOM_CYCLE",
              row,
              "同一料号出现在自己的祖先链中: "
                  + String.join(" -> ", ancestorMaterials)
                  + " -> "
                  + materialCode));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    }

    EffectiveBomShapeDecision decision = decisions.get(materialCode);
    if (decision == null) {
      state.issues().add(
          issue(
              "SHAPE_RESOLUTION_MISSING",
              row,
              "当前可达节点没有形态解析结果"));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    }
    if (decision.blocked()) {
      state.issues().add(
          issue(
              "SHAPE_RESOLUTION_BLOCKED",
              row,
              "形态解析被阻断: " + decision.blockingReason()));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    }

    BigDecimal qtyPerParent;
    BigDecimal qtyPerTop;
    if (parent == null) {
      qtyPerParent = BigDecimal.ONE;
      qtyPerTop = BigDecimal.ONE;
    } else if (row.getQtyPerParent() == null) {
      state.issues().add(issue("QUANTITY_MISSING", row, "相对父级用量为空"));
      markSubtreeHandled(path, structure, state.handledPaths());
      return;
    } else {
      qtyPerParent = row.getQtyPerParent();
      qtyPerTop = parentQtyPerTop.multiply(qtyPerParent);
    }

    EffectiveBomNodeDraft draft =
        draft(
            row,
            parent,
            depth,
            path,
            qtyPerParent,
            qtyPerTop,
            decision,
            selectionSourceByGroup);
    state.nodes().add(draft);
    state.handledPaths().add(path);

    List<BomRawHierarchy> children =
        structure.childrenByParentPath().getOrDefault(path, List.of());
    if (decision.effectiveShape() == QuoteMaterialShape.PURCHASE) {
      List<BomRawHierarchy> descendants = descendants(path, structure);
      if (!descendants.isEmpty()) {
        state.exclusions().add(
            new EffectiveBomExclusion(
                "PURCHASE_DESCENDANT_CUT",
                materialCode,
                path,
                descendants.getFirst().getMaterialCode(),
                normalizePath(descendants.getFirst().getPath()),
                descendants.size(),
                "采购件保留自身并截断全部后代"));
        descendants.stream()
            .map(BomRawHierarchy::getPath)
            .map(QuoteEffectiveBomBuilderImpl::normalizePath)
            .forEach(state.handledPaths()::add);
      }
      return;
    }

    List<BomRawHierarchy> retainedChildren = children;
    if (decision.effectiveShape() == QuoteMaterialShape.OUTSOURCE) {
      EffectiveBomPolicyAction action;
      try {
        action = policyActionResolver.resolve(decision);
      } catch (IllegalArgumentException ex) {
        state.issues().add(
            issue(
                "POLICY_ACTION_INVALID",
                row,
                "形态规则动作配置无效: " + ex.getMessage()));
        descendants(path, structure).stream()
            .map(BomRawHierarchy::getPath)
            .map(QuoteEffectiveBomBuilderImpl::normalizePath)
            .forEach(state.handledPaths()::add);
        return;
      }
      retainedChildren =
          excludeConfiguredDirectChildren(
              row, path, children, action, structure, state);
    }
    if (decision.effectiveShape() == QuoteMaterialShape.MANUFACTURE
        && retainedChildren.isEmpty()) {
      state.issues().add(
          issue(
              "BOM_GAP",
              row,
              "制造件没有可用下级结构，需要进入补BOM流程"));
      return;
    }

    ancestorMaterials.add(materialCode);
    for (BomRawHierarchy child : retainedChildren) {
      visit(
          child,
          draft,
          qtyPerTop,
          depth + 1,
          ancestorMaterials,
          structure,
          decisions,
          selectionSourceByGroup,
          maxDepth,
          state);
    }
    ancestorMaterials.remove(materialCode);
  }

  private static List<BomRawHierarchy> excludeConfiguredDirectChildren(
      BomRawHierarchy parent,
      String parentPath,
      List<BomRawHierarchy> children,
      EffectiveBomPolicyAction action,
      Structure structure,
      BuildState state) {
    if (action.excludedDirectChildMaterialCodes().isEmpty()) {
      return children;
    }
    List<BomRawHierarchy> retained = new ArrayList<>();
    for (BomRawHierarchy child : children) {
      String childMaterialCode = trimToNull(child.getMaterialCode());
      if (!action.excludedDirectChildMaterialCodes().contains(childMaterialCode)) {
        retained.add(child);
        continue;
      }
      String childPath = normalizePath(child.getPath());
      List<BomRawHierarchy> excludedSubtree = new ArrayList<>();
      excludedSubtree.add(child);
      excludedSubtree.addAll(descendants(childPath, structure));
      state.exclusions().add(
          new EffectiveBomExclusion(
              "POLICY_DIRECT_CHILD_EXCLUSION",
              trimToNull(parent.getMaterialCode()),
              parentPath,
              childMaterialCode,
              childPath,
              excludedSubtree.size(),
              "规则动作排除匹配的直接子件及其全部后代"));
      excludedSubtree.stream()
          .map(BomRawHierarchy::getPath)
          .map(QuoteEffectiveBomBuilderImpl::normalizePath)
          .forEach(state.handledPaths()::add);
    }
    return retained;
  }

  private static EffectiveBomNodeDraft draft(
      BomRawHierarchy row,
      EffectiveBomNodeDraft parent,
      int depth,
      String path,
      BigDecimal qtyPerParent,
      BigDecimal qtyPerTop,
      EffectiveBomShapeDecision decision,
      Map<String, String> selectionSourceByGroup) {
    String groupKey = trimToNull(row.getAlternativeGroupKey());
    return new EffectiveBomNodeDraft(
        nodeKey(row),
        parent == null ? null : parent.nodeKey(),
        depth,
        row.getSortSeq() == null ? 0 : row.getSortSeq(),
        path,
        trimToNull(row.getMaterialCode()),
        trimToNull(row.getMaterialName()),
        trimToNull(row.getMaterialSpec()),
        trimToNull(row.getPriceOrgCode()),
        qtyPerParent,
        qtyPerTop,
        decision.sourceMaterialShape(),
        decision.effectiveShape(),
        decision.resolutionSource(),
        decision.shapePolicyId(),
        decision.shapePolicyFingerprint(),
        decision.selectedSupplierRatioId(),
        decision.selectedSupplierCode(),
        decision.selectedSupplierName(),
        decision.selectedSupplyRatio(),
        groupKey,
        trimToNull(row.getChildType()),
        groupKey == null ? null : selectionSourceByGroup.get(groupKey),
        trimToNull(row.getSourceType()),
        trimToNull(row.getBuildBatchId()),
        row.getId(),
        path);
  }

  private static Structure indexStructure(List<BomRawHierarchy> nodes) {
    Map<String, BomRawHierarchy> nodeByPath = new LinkedHashMap<>();
    Map<String, List<BomRawHierarchy>> childrenByParentPath = new HashMap<>();
    List<EffectiveBomBlockIssue> issues = new ArrayList<>();
    List<BomRawHierarchy> sorted =
        nodes.stream()
            .sorted(
                Comparator.<BomRawHierarchy>comparingInt(
                        row -> row.getLevel() == null ? Integer.MAX_VALUE : row.getLevel())
                    .thenComparing(
                        row -> normalizePath(row.getPath()),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(NODE_ORDER))
            .toList();
    for (BomRawHierarchy row : sorted) {
      String path = normalizePath(row.getPath());
      if (path == null) {
        issues.add(issue("PATH_MISSING", row, "节点路径为空"));
        continue;
      }
      BomRawHierarchy old = nodeByPath.putIfAbsent(path, row);
      if (old != null) {
        issues.add(issue("NODE_PATH_DUPLICATE", row, "节点路径重复: " + path));
      }
    }
    if (!issues.isEmpty()) {
      return new Structure(null, nodeByPath, Map.of(), issues);
    }

    List<BomRawHierarchy> roots = new ArrayList<>();
    for (Map.Entry<String, BomRawHierarchy> entry : nodeByPath.entrySet()) {
      String parentPath = parentPath(entry.getKey());
      if (parentPath == null) {
        roots.add(entry.getValue());
        continue;
      }
      BomRawHierarchy parent = nodeByPath.get(parentPath);
      if (parent == null) {
        issues.add(
            issue(
                "PARENT_PATH_MISSING",
                entry.getValue(),
                "找不到直接父路径: " + parentPath));
        continue;
      }
      if (StringUtils.hasText(entry.getValue().getParentCode())
          && !entry.getValue().getParentCode().trim().equals(parent.getMaterialCode())) {
        issues.add(
            issue(
                "PARENT_CODE_MISMATCH",
                entry.getValue(),
                "父料号与父路径节点不一致"));
      }
      childrenByParentPath
          .computeIfAbsent(parentPath, ignored -> new ArrayList<>())
          .add(entry.getValue());
    }
    for (List<BomRawHierarchy> children : childrenByParentPath.values()) {
      children.sort(NODE_ORDER);
    }
    if (roots.size() != 1) {
      issues.add(
          new EffectiveBomBlockIssue(
              "ROOT_COUNT_INVALID",
              null,
              null,
              "最终候选树必须恰好一个根节点，当前数量=" + roots.size()));
    }
    return new Structure(
        roots.size() == 1 ? roots.getFirst() : null,
        nodeByPath,
        childrenByParentPath,
        issues);
  }

  private static SelectionContext resolveSelections(
      EffectiveBomBuildRequest request) {
    Map<String, String> selections =
        new LinkedHashMap<>(request.selectedMaterialCodeByGroupKey());
    Map<String, String> sources = new LinkedHashMap<>();
    for (BomAlternativeGroup group : request.alternativeGroups()) {
      String key = trimToNull(group.alternativeGroupKey());
      if (key == null) {
        return new SelectionContext(
            Map.of(),
            Map.of(),
            new EffectiveBomBlockIssue(
                "ALT_GROUP_KEY_MISSING", null, null, "替代组键为空"));
      }
      String selected = trimToNull(selections.get(key));
      if (selected == null) {
        try {
          selected = group.standardCandidate().materialCode();
        } catch (RuntimeException ex) {
          return new SelectionContext(
              Map.of(),
              Map.of(),
              new EffectiveBomBlockIssue(
                  "ALT_DEFAULT_STANDARD_INVALID",
                  null,
                  null,
                  "替代组无法确定唯一默认标准料: group=" + key));
        }
        selections.put(key, selected);
        sources.put(key, "AUTO_DEFAULT");
      } else {
        sources.put(key, "MANUAL");
      }
    }
    return new SelectionContext(selections, sources, null);
  }

  private static List<EffectiveBomExclusion> alternativeExclusions(
      List<BomRawHierarchy> input, List<BomRawHierarchy> retained) {
    Set<String> retainedIdentities = new HashSet<>();
    retained.forEach(row -> retainedIdentities.add(identity(row)));
    List<BomRawHierarchy> removed =
        input.stream()
            .filter(row -> !retainedIdentities.contains(identity(row)))
            .toList();
    Set<String> removedPaths = new HashSet<>();
    removed.stream()
        .map(BomRawHierarchy::getPath)
        .map(QuoteEffectiveBomBuilderImpl::normalizePath)
        .filter(java.util.Objects::nonNull)
        .forEach(removedPaths::add);
    return removed.stream()
        .filter(row -> !removedPaths.contains(parentPath(normalizePath(row.getPath()))))
        .sorted(NODE_ORDER)
        .map(
            root -> {
              String rootPath = normalizePath(root.getPath());
              int count =
                  (int)
                      removed.stream()
                          .map(BomRawHierarchy::getPath)
                          .map(QuoteEffectiveBomBuilderImpl::normalizePath)
                          .filter(path -> path != null && path.startsWith(rootPath))
                          .count();
              return new EffectiveBomExclusion(
                  "ALTERNATIVE_UNSELECTED",
                  trimToNull(root.getParentCode()),
                  parentPath(rootPath),
                  trimToNull(root.getMaterialCode()),
                  rootPath,
                  count,
                  "未选中的标准/替代分支整支排除");
            })
        .toList();
  }

  private static Map<String, EffectiveBomShapeDecision> normalizeDecisions(
      Map<String, EffectiveBomShapeDecision> source) {
    Map<String, EffectiveBomShapeDecision> normalized = new LinkedHashMap<>();
    source.forEach(
        (key, value) -> {
          String materialCode = trimToNull(key);
          if (materialCode != null && value != null) {
            normalized.put(materialCode, value);
          }
        });
    return normalized;
  }

  private static List<BomRawHierarchy> descendants(
      String parentPath, Structure structure) {
    List<BomRawHierarchy> result = new ArrayList<>();
    List<BomRawHierarchy> stack =
        new ArrayList<>(structure.childrenByParentPath().getOrDefault(parentPath, List.of()));
    Set<String> visited = new HashSet<>();
    while (!stack.isEmpty()) {
      BomRawHierarchy current = stack.removeFirst();
      String path = normalizePath(current.getPath());
      if (!visited.add(path)) {
        continue;
      }
      result.add(current);
      stack.addAll(structure.childrenByParentPath().getOrDefault(path, List.of()));
    }
    result.sort(
        Comparator.<BomRawHierarchy>comparingInt(
                row -> row.getLevel() == null ? Integer.MAX_VALUE : row.getLevel())
            .thenComparing(
                row -> normalizePath(row.getPath()),
                Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NODE_ORDER));
    return result;
  }

  private static void markSubtreeHandled(
      String path, Structure structure, Set<String> handledPaths) {
    handledPaths.add(path);
    descendants(path, structure).stream()
        .map(BomRawHierarchy::getPath)
        .map(QuoteEffectiveBomBuilderImpl::normalizePath)
        .forEach(handledPaths::add);
  }

  private static EffectiveBomBuildResult failed(
      String code, String materialCode, String path, String message) {
    return result(
        List.of(),
        List.of(),
        List.of(new EffectiveBomBlockIssue(code, materialCode, path, message)),
        List.of());
  }

  private static EffectiveBomBuildResult result(
      List<EffectiveBomNodeDraft> nodes,
      List<EffectiveBomExclusion> exclusions,
      List<EffectiveBomBlockIssue> issues,
      List<String> warnings) {
    return new EffectiveBomBuildResult(nodes, exclusions, issues, warnings);
  }

  private static EffectiveBomBlockIssue issue(
      String code, BomRawHierarchy row, String message) {
    return new EffectiveBomBlockIssue(
        code,
        row == null ? null : trimToNull(row.getMaterialCode()),
        row == null ? null : normalizePath(row.getPath()),
        message);
  }

  private static String nodeKey(BomRawHierarchy row) {
    String sourceLineKey = trimToNull(row.getSourceLineKey());
    if (sourceLineKey != null) {
      return "SOURCE:" + sourceLineKey;
    }
    if (row.getId() != null) {
      return "HIERARCHY:" + row.getId();
    }
    return "PATH:" + normalizePath(row.getPath());
  }

  private static String identity(BomRawHierarchy row) {
    if (row.getId() != null) {
      return "ID:" + row.getId();
    }
    return nodeKey(row);
  }

  private static String parentPath(String path) {
    if (path == null || "/".equals(path)) {
      return null;
    }
    String withoutTrailing = path.substring(0, path.length() - 1);
    int lastSlash = withoutTrailing.lastIndexOf('/');
    if (lastSlash <= 0) {
      return null;
    }
    return withoutTrailing.substring(0, lastSlash + 1);
  }

  private static String normalizePath(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return null;
    }
    normalized = normalized.replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    return normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String text(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? "" : normalized;
  }

  private record SelectionContext(
      Map<String, String> selections,
      Map<String, String> selectionSourceByGroup,
      EffectiveBomBlockIssue failure) {}

  private record Structure(
      BomRawHierarchy root,
      Map<String, BomRawHierarchy> nodeByPath,
      Map<String, List<BomRawHierarchy>> childrenByParentPath,
      List<EffectiveBomBlockIssue> issues) {}

  private record BuildState(
      List<EffectiveBomExclusion> exclusions,
      List<EffectiveBomNodeDraft> nodes,
      List<EffectiveBomBlockIssue> issues,
      Set<String> handledPaths) {}
}
