package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.BomRawHierarchy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 按候选路径发生位置裁剪标准/替代分支。
 *
 * <p>本组件只读取内存节点，不修改节点、不重算用量，也不访问数据库。未选中候选的路径
 * 前缀连同全部后代一起排除；嵌套组按根到叶顺序处理。
 */
@Component
public final class BomAlternativeBranchPrunerImpl
    implements BomAlternativeBranchPruner {

  public static final String ALT_SELECTION_MISSING =
      "ALT_SELECTION_MISSING";
  public static final String ALT_GROUP_NOT_FOUND = "ALT_GROUP_NOT_FOUND";
  public static final String ALT_CANDIDATE_INVALID =
      "ALT_CANDIDATE_INVALID";
  public static final String ALT_BRANCH_STRUCTURE_MISSING =
      "ALT_BRANCH_STRUCTURE_MISSING";
  public static final String ALT_UNSELECTED_BRANCH_MISSING =
      "ALT_UNSELECTED_BRANCH_MISSING";

  private static final Comparator<BomRawHierarchy> NODE_ORDER =
      Comparator.comparingInt(BomAlternativeBranchPrunerImpl::level)
          .thenComparing(
              row -> normalizePath(row.getPath()),
              Comparator.naturalOrder())
          .thenComparingInt(
              row ->
                  row.getSortSeq() == null
                      ? Integer.MAX_VALUE
                      : row.getSortSeq())
          .thenComparing(
              row -> normalized(row.getProcessSeq()),
              Comparator.naturalOrder())
          .thenComparing(
              row -> normalized(row.getSourceLineKey()),
              Comparator.naturalOrder())
          .thenComparing(
              BomRawHierarchy::getId,
              Comparator.nullsLast(Comparator.naturalOrder()));

  private static final Comparator<BomAlternativeGroup> GROUP_ORDER =
      Comparator.comparingInt(BomAlternativeBranchPrunerImpl::groupDepth)
          .thenComparing(
              group -> groupKey(group),
              Comparator.naturalOrder());

  @Override
  public BomAlternativePruneResult prune(
      BomAlternativePruneRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("BOM替代分支裁剪请求不能为空");
    }

    List<BomRawHierarchy> inputNodes = request.nodes();
    List<BomAlternativeGroup> groups =
        request.groups().stream().sorted(GROUP_ORDER).toList();
    Set<String> existingPaths = new LinkedHashSet<>();
    for (BomRawHierarchy node : inputNodes) {
      String path = normalizePath(node.getPath());
      if (!path.isEmpty()) {
        existingPaths.add(path);
      }
    }

    Set<String> removedPrefixes = new LinkedHashSet<>();
    List<String> processedGroupKeys = new ArrayList<>();
    List<String> skippedGroupKeys = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    List<SelectedBranch> selectedBranches = new ArrayList<>();
    Map<String, String> selections =
        request.selectedMaterialCodeByGroupKey();

    for (BomAlternativeGroup group : groups) {
      String key = requiredGroupKey(group);
      List<CandidatePath> candidates = candidates(group);
      boolean anyReachable =
          candidates.stream()
              .map(CandidatePath::path)
              .anyMatch(path -> !isRemoved(path, removedPrefixes));
      if (!anyReachable) {
        skippedGroupKeys.add(key);
        continue;
      }

      String selectedMaterialCode = selections.get(key);
      if (!StringUtils.hasText(selectedMaterialCode)) {
        throw failure(
            ALT_SELECTION_MISSING,
            "当前可达替代组没有生效选择",
            group,
            null);
      }
      CandidatePath selected =
          candidates.stream()
              .filter(
                  candidate ->
                      sameText(
                          candidate.candidate().materialCode(),
                          selectedMaterialCode))
              .findFirst()
              .orElseThrow(
                  () ->
                      failure(
                          ALT_CANDIDATE_INVALID,
                          "所选料号不属于当前替代组",
                          group,
                          selectedMaterialCode));

      if (selected.path().isEmpty()
          || isRemoved(selected.path(), removedPrefixes)
          || !existingPaths.contains(selected.path())) {
        throw failure(
            ALT_BRANCH_STRUCTURE_MISSING,
            "选中分支根节点或路径不存在",
            group,
            selected.candidate().materialCode());
      }
      selectedBranches.add(new SelectedBranch(group, selected));

      for (CandidatePath candidate : candidates) {
        if (candidate == selected) {
          continue;
        }
        if (candidate.path().isEmpty()
            || !existingPaths.contains(candidate.path())) {
          warnings.add(
              unselectedMissingWarning(group, candidate.candidate()));
        }
        if (!candidate.path().isEmpty()) {
          removedPrefixes.add(candidate.path());
        }
      }
      processedGroupKeys.add(key);
    }

    List<BomRawHierarchy> outputNodes =
        inputNodes.stream()
            .filter(
                node ->
                    !isRemoved(
                        normalizePath(node.getPath()), removedPrefixes))
            .sorted(NODE_ORDER)
            .toList();
    validateSelectedSubtrees(outputNodes, selectedBranches);
    return new BomAlternativePruneResult(
        outputNodes,
        inputNodes.size(),
        outputNodes.size(),
        inputNodes.size() - outputNodes.size(),
        processedGroupKeys.size(),
        skippedGroupKeys.size(),
        processedGroupKeys,
        skippedGroupKeys,
        warnings);
  }

  private static List<CandidatePath> candidates(
      BomAlternativeGroup group) {
    if (group == null || group.candidates() == null) {
      return List.of();
    }
    List<CandidatePath> candidates =
        group.candidates().stream()
            .filter(Objects::nonNull)
            .map(
                candidate ->
                    new CandidatePath(
                        candidate, normalizePath(candidate.path())))
            .toList();
    if (candidates.isEmpty()) {
      throw failure(
          ALT_BRANCH_STRUCTURE_MISSING,
          "替代组没有候选成员",
          group,
          null);
    }
    return candidates;
  }

  private static String requiredGroupKey(BomAlternativeGroup group) {
    String key = groupKey(group);
    if (key.isEmpty()) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_GROUP_NOT_FOUND,
          "替代组键为空，无法执行分支裁剪");
    }
    return key;
  }

  private static int groupDepth(BomAlternativeGroup group) {
    return group == null || group.candidates() == null
        ? Integer.MAX_VALUE
        : group.candidates().stream()
            .filter(Objects::nonNull)
            .map(BomAlternativeCandidate::path)
            .map(BomAlternativeBranchPrunerImpl::normalizePath)
            .filter(path -> !path.isEmpty())
            .mapToInt(BomAlternativeBranchPrunerImpl::pathDepth)
            .min()
            .orElse(Integer.MAX_VALUE);
  }

  private static int pathDepth(String normalizedPath) {
    if (!StringUtils.hasText(normalizedPath)
        || "/".equals(normalizedPath)) {
      return 0;
    }
    int depth = 0;
    for (int index = 0; index < normalizedPath.length(); index++) {
      if (normalizedPath.charAt(index) == '/') {
        depth++;
      }
    }
    return Math.max(0, depth - 1);
  }

  private static boolean isRemoved(
      String path, Set<String> removedPrefixes) {
    if (path.isEmpty()) {
      return false;
    }
    return removedPrefixes.stream().anyMatch(path::startsWith);
  }

  private static void validateSelectedSubtrees(
      List<BomRawHierarchy> outputNodes,
      List<SelectedBranch> selectedBranches) {
    Set<String> outputPaths =
        outputNodes.stream()
            .map(BomRawHierarchy::getPath)
            .map(BomAlternativeBranchPrunerImpl::normalizePath)
            .filter(path -> !path.isEmpty())
            .collect(
                java.util.stream.Collectors.toCollection(
                    LinkedHashSet::new));
    for (SelectedBranch selectedBranch : selectedBranches) {
      String selectedPath = selectedBranch.candidate().path();
      for (String path : outputPaths) {
        if (!path.startsWith(selectedPath)) {
          continue;
        }
        String parentPath = parentPath(path);
        if (!parentPath.isEmpty() && !outputPaths.contains(parentPath)) {
          throw failure(
              ALT_BRANCH_STRUCTURE_MISSING,
              "选中分支父子路径链断裂，缺少父路径" + parentPath,
              selectedBranch.group(),
              selectedBranch.candidate().candidate().materialCode());
        }
      }
    }
  }

  private static String parentPath(String normalizedPath) {
    if (normalizedPath.isEmpty() || "/".equals(normalizedPath)) {
      return "";
    }
    String withoutTrailingSlash =
        normalizedPath.substring(0, normalizedPath.length() - 1);
    int lastSlash = withoutTrailingSlash.lastIndexOf('/');
    if (lastSlash < 0) {
      return "";
    }
    return normalizedPath.substring(0, lastSlash + 1);
  }

  private static QuoteBomAlternativeSelectionException failure(
      String code,
      String reason,
      BomAlternativeGroup group,
      String selectedMaterialCode) {
    return new QuoteBomAlternativeSelectionException(
        code,
        reason
            + "；"
            + context(group, selectedMaterialCode)
            + "；请刷新BOM并确认该位置的标准/替代结构");
  }

  private static String unselectedMissingWarning(
      BomAlternativeGroup group, BomAlternativeCandidate candidate) {
    return ALT_UNSELECTED_BRANCH_MISSING
        + ": 未选中分支根节点缺失，已按路径排除其残留后代；"
        + context(
            group,
            candidate == null ? null : candidate.materialCode());
  }

  private static String context(
      BomAlternativeGroup group, String selectedMaterialCode) {
    BomAlternativeGroupIdentity identity =
        group == null ? null : group.identity();
    String standard =
        group == null
            ? ""
            : group.candidates().stream()
                .filter(Objects::nonNull)
                .filter(
                    candidate ->
                        candidate.childType() == BomChildType.STANDARD)
                .map(BomAlternativeCandidate::materialCode)
                .findFirst()
                .orElse("");
    String alternatives =
        group == null
            ? ""
            : String.join(
                ",",
                group.candidates().stream()
                    .filter(Objects::nonNull)
                    .filter(
                        candidate ->
                            candidate.childType()
                                == BomChildType.ALTERNATIVE)
                    .map(BomAlternativeCandidate::materialCode)
                    .filter(Objects::nonNull)
                    .toList());
    return "替代组="
        + groupKey(group)
        + ", 父件="
        + display(identity == null ? null : identity.parentMaterialNo())
        + ", 子项="
        + (identity == null ? null : identity.childSeq())
        + ", 标准件="
        + display(standard)
        + ", 替代件="
        + display(alternatives)
        + ", 当前选择="
        + display(selectedMaterialCode)
        + ", BOM目的="
        + display(identity == null ? null : identity.bomPurpose())
        + ", BOM版本="
        + display(identity == null ? null : identity.bomVersion());
  }

  private static int level(BomRawHierarchy row) {
    return row == null || row.getLevel() == null
        ? Integer.MAX_VALUE
        : row.getLevel();
  }

  private static String groupKey(BomAlternativeGroup group) {
    return group == null || group.alternativeGroupKey() == null
        ? ""
        : group.alternativeGroupKey().strip();
  }

  private static String normalizePath(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String normalized =
        value.strip().replace('\\', '/').replaceAll("/+", "/");
    if (!normalized.startsWith("/")) {
      normalized = "/" + normalized;
    }
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private static String normalized(String value) {
    return BomChildType.normalize(value);
  }

  private static boolean sameText(String left, String right) {
    return normalized(left).equals(normalized(right));
  }

  private static String display(Object value) {
    if (value == null || !StringUtils.hasText(String.valueOf(value))) {
      return "（空）";
    }
    return String.valueOf(value).strip();
  }

  private record CandidatePath(
      BomAlternativeCandidate candidate, String path) {
  }

  private record SelectedBranch(
      BomAlternativeGroup group, CandidatePath candidate) {
  }
}
