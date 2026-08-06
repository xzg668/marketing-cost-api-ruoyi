package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.dto.quotebom.QuoteBomReadContext;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 报价感知的替代选择解析器。
 *
 * <p>按根到叶顺序逐组处理，父分支被裁掉后，其下级替代组不会建立无效的默认选择。
 */
@Component
public final class QuoteAwareBomAlternativeResolverImpl
    implements QuoteAwareBomAlternativeResolver {

  private static final String ALT_SOURCE_STALE = "ALT_SOURCE_STALE";

  private final BomAlternativeGroupResolver groupResolver;
  private final QuoteBomAlternativeSelectionService selectionService;
  private final BomAlternativeBranchPruner branchPruner;

  public QuoteAwareBomAlternativeResolverImpl(
      BomAlternativeGroupResolver groupResolver,
      QuoteBomAlternativeSelectionService selectionService,
      BomAlternativeBranchPruner branchPruner) {
    this.groupResolver = Objects.requireNonNull(groupResolver, "groupResolver");
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.branchPruner = Objects.requireNonNull(branchPruner, "branchPruner");
  }

  @Override
  public BomAlternativePruneResult resolve(
      QuoteBomReadContext context, List<BomRawHierarchy> effectiveRows) {
    if (context == null) {
      throw new IllegalArgumentException("报价BOM读取上下文不能为空");
    }
    List<BomRawHierarchy> inputRows =
        effectiveRows == null
            ? List.of()
            : effectiveRows.stream().filter(Objects::nonNull).toList();
    BomAlternativeGroupResolution resolution = groupResolver.resolve(inputRows);
    List<BomRawHierarchy> currentRows = inputRows;
    List<String> processedKeys = new ArrayList<>();
    List<String> skippedKeys = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    QuoteBomAlternativeSelectionScope scope = scope(context);

    List<BomAlternativeGroup> groups =
        resolution.groups().stream()
            .sorted(
                Comparator.comparingInt(
                        QuoteAwareBomAlternativeResolverImpl::groupDepth)
                    .thenComparing(BomAlternativeGroup::alternativeGroupKey))
            .toList();
    for (BomAlternativeGroup group : groups) {
      if (!isReachable(group, currentRows)) {
        skippedKeys.add(group.alternativeGroupKey());
        continue;
      }
      QuoteBomAlternativeSelectionResult selection =
          selectionService.ensureDefault(scope, group);
      validateActiveSelection(group, selection);
      BomAlternativePruneResult pruned =
          branchPruner.prune(
              new BomAlternativePruneRequest(
                  currentRows,
                  List.of(group),
                  Map.of(
                      group.alternativeGroupKey(),
                      selection.selectedMaterialCode())));
      currentRows = pruned.nodes();
      processedKeys.addAll(pruned.processedGroupKeys());
      skippedKeys.addAll(pruned.skippedGroupKeys());
      warnings.addAll(pruned.warnings());
    }

    for (BomAlternativeGroupIssue issue : resolution.issues()) {
      if (isIssueReachable(issue, currentRows)) {
        throw new QuoteBomAlternativeSelectionException(
            issue.code(), issue.code() + ": " + issue.message());
      }
    }
    return new BomAlternativePruneResult(
        currentRows,
        inputRows.size(),
        currentRows.size(),
        inputRows.size() - currentRows.size(),
        processedKeys.size(),
        skippedKeys.size(),
        processedKeys,
        skippedKeys,
        warnings);
  }

  private static QuoteBomAlternativeSelectionScope scope(
      QuoteBomReadContext context) {
    return new QuoteBomAlternativeSelectionScope(
        context.oaNo(),
        context.oaFormItemId(),
        context.topProductCode(),
        context.periodMonth(),
        context.priceOrgCode(),
        context.businessUnitType());
  }

  private static void validateActiveSelection(
      BomAlternativeGroup group,
      QuoteBomAlternativeSelectionResult selection) {
    if (selection == null
        || selection.reviewRequired()
        || !selection.persisted()
        || !QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
            selection.selectionStatus())
        || !StringUtils.hasText(selection.selectedMaterialCode())) {
      throw new QuoteBomAlternativeSelectionException(
          ALT_SOURCE_STALE,
          ALT_SOURCE_STALE
              + ": 替代组"
              + group.alternativeGroupKey()
              + "的历史选择已失效，请报价员重新确认");
    }
  }

  private static boolean isReachable(
      BomAlternativeGroup group, List<BomRawHierarchy> rows) {
    Set<String> currentPaths = paths(rows);
    return group.candidates().stream()
        .map(BomAlternativeCandidate::path)
        .map(QuoteAwareBomAlternativeResolverImpl::normalizePath)
        .anyMatch(currentPaths::contains);
  }

  private static boolean isIssueReachable(
      BomAlternativeGroupIssue issue, List<BomRawHierarchy> rows) {
    String parentPath = normalizePath(issue.parentPath());
    if (parentPath.isEmpty()) {
      return true;
    }
    return paths(rows).stream()
        .anyMatch(
            path ->
                path.equals(parentPath)
                    || path.startsWith(parentPath));
  }

  private static Set<String> paths(List<BomRawHierarchy> rows) {
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    for (BomRawHierarchy row : rows) {
      String path = normalizePath(row.getPath());
      if (!path.isEmpty()) {
        paths.add(path);
      }
    }
    return paths;
  }

  private static int groupDepth(BomAlternativeGroup group) {
    return group.candidates().stream()
        .map(BomAlternativeCandidate::path)
        .map(QuoteAwareBomAlternativeResolverImpl::normalizePath)
        .filter(StringUtils::hasText)
        .mapToInt(QuoteAwareBomAlternativeResolverImpl::pathDepth)
        .min()
        .orElse(Integer.MAX_VALUE);
  }

  private static int pathDepth(String path) {
    int depth = 0;
    for (int index = 0; index < path.length(); index++) {
      if (path.charAt(index) == '/') {
        depth++;
      }
    }
    return Math.max(0, depth - 1);
  }

  private static String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    String value = path.trim().replace('\\', '/').replaceAll("/+", "/");
    if (!value.startsWith("/")) {
      value = "/" + value;
    }
    return value.endsWith("/") ? value : value + "/";
  }
}
