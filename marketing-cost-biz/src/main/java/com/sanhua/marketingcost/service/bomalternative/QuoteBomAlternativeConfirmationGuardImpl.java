package com.sanhua.marketingcost.service.bomalternative;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.service.impl.BomEffectiveTreePruner;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 使用当前正式 BOM 同步可达替代组，防止失效选择进入确认快照。 */
@Service
public class QuoteBomAlternativeConfirmationGuardImpl
    implements QuoteBomAlternativeConfirmationGuard {

  private static final String ALT_SOURCE_STALE = "ALT_SOURCE_STALE";

  private final BomRawHierarchyMapper rawHierarchyMapper;
  private final BomAlternativeGroupResolver groupResolver;
  private final BomAlternativeBranchPruner branchPruner;
  private final QuoteBomAlternativeSelectionService selectionService;

  public QuoteBomAlternativeConfirmationGuardImpl(
      BomRawHierarchyMapper rawHierarchyMapper,
      BomAlternativeGroupResolver groupResolver,
      BomAlternativeBranchPruner branchPruner,
      QuoteBomAlternativeSelectionService selectionService) {
    this.rawHierarchyMapper = rawHierarchyMapper;
    this.groupResolver = groupResolver;
    this.branchPruner = branchPruner;
    this.selectionService = selectionService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int validateAndCountManualAlternatives(
      QuoteBomAlternativeSelectionScope scope,
      LocalDate quoteDate,
      String bomPurpose) {
    if (scope == null) {
      throw new IllegalArgumentException("替代选择作用域不能为空");
    }
    if (quoteDate == null) {
      throw new IllegalArgumentException("报价日期不能为空");
    }
    String purpose = required("bomPurpose", bomPurpose);
    List<BomRawHierarchy> rows =
        loadEffectiveRows(scope, quoteDate, purpose);
    if (rows.isEmpty()) {
      throw stale(
          "当前正式BOM不存在或有效期已变化，不能确认报价物料明细");
    }
    BomAlternativeGroupResolution resolution =
        groupResolver.resolve(rows);
    ReachableSnapshot reachable =
        reachableGroups(scope, rows, resolution.groups());
    for (BomAlternativeGroupIssue issue : resolution.issues()) {
      if (isIssueReachable(issue, reachable.rows())) {
        throw stale(
            "当前正式BOM的标准/替代结构异常："
                + issue.message()
                + "；父件="
                + display(issue.parentMaterialNo())
                + "；请维护BOM后重新确认");
      }
    }
    List<QuoteBomAlternativeSelectionResult> selections =
        selectionService.synchronize(
            scope, reachable.groups());
    List<QuoteBomAlternativeSelectionResult> pendingReview =
        selections.stream()
            .filter(
                selection ->
                    selection.reviewRequired()
                        || !selection.persisted()
                        || !QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
                            selection.selectionStatus()))
            .toList();
    if (!pendingReview.isEmpty()) {
      throw stale(
          "BOM版本或有效期已变化，存在"
              + pendingReview.size()
              + "个替代组需要重新确认；替代组="
              + pendingReview.stream()
                  .map(
                      QuoteBomAlternativeSelectionResult
                          ::alternativeGroupKey)
                  .toList());
    }
    return (int)
        selections.stream()
            .filter(
                selection ->
                    QuoteBomAlternativeSelection
                        .SOURCE_MANUAL_ALTERNATIVE
                        .equals(selection.selectionSource()))
            .count();
  }

  private List<BomRawHierarchy> loadEffectiveRows(
      QuoteBomAlternativeSelectionScope scope,
      LocalDate quoteDate,
      String bomPurpose) {
    List<BomRawHierarchy> rows =
        rawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(
                    BomRawHierarchy::getPriceOrgCode,
                    required("priceOrgCode", scope.priceOrgCode()))
                .eq(
                    BomRawHierarchy::getTopProductCode,
                    required(
                        "topProductCode",
                        scope.topProductCode()))
                .eq(BomRawHierarchy::getBomPurpose, bomPurpose)
                .le(BomRawHierarchy::getEffectiveFrom, quoteDate)
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(
                                BomRawHierarchy::getEffectiveTo,
                                quoteDate))
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    return BomEffectiveTreePruner.prune(
        rows == null ? List.of() : rows,
        scope.topProductCode());
  }

  private ReachableSnapshot reachableGroups(
      QuoteBomAlternativeSelectionScope scope,
      List<BomRawHierarchy> sourceRows,
      List<BomAlternativeGroup> resolvedGroups) {
    List<BomAlternativeGroup> ordered =
        (resolvedGroups == null ? List.<BomAlternativeGroup>of() : resolvedGroups)
            .stream()
            .sorted(
                Comparator.comparingInt(
                        QuoteBomAlternativeConfirmationGuardImpl
                            ::groupDepth)
                    .thenComparing(
                        BomAlternativeGroup::alternativeGroupKey))
            .toList();
    List<BomRawHierarchy> currentRows = sourceRows;
    List<BomAlternativeGroup> reachable = new ArrayList<>();
    for (BomAlternativeGroup group : ordered) {
      if (!isReachable(group, currentRows)) {
        continue;
      }
      QuoteBomAlternativeSelectionResult current =
          selectionService.findCurrent(
              scope, group.alternativeGroupKey());
      String selectedMaterial =
          selectedMaterialForReachability(group, current);
      currentRows =
          branchPruner
              .prune(
                  new BomAlternativePruneRequest(
                      currentRows,
                      List.of(group),
                      Map.of(
                          group.alternativeGroupKey(),
                          selectedMaterial)))
              .nodes();
      reachable.add(group);
    }
    return new ReachableSnapshot(
        List.copyOf(reachable), List.copyOf(currentRows));
  }

  private static String selectedMaterialForReachability(
      BomAlternativeGroup group,
      QuoteBomAlternativeSelectionResult current) {
    if (current != null
        && current.persisted()
        && !current.reviewRequired()
        && QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(
            current.selectionStatus())
        && group.candidates().stream()
            .anyMatch(
                candidate ->
                    sameText(
                        candidate.materialCode(),
                        current.selectedMaterialCode()))) {
      return current.selectedMaterialCode();
    }
    return group.standardCandidate().materialCode();
  }

  private static boolean isReachable(
      BomAlternativeGroup group, List<BomRawHierarchy> rows) {
    Set<String> paths = paths(rows);
    return group.candidates().stream()
        .map(BomAlternativeCandidate::path)
        .map(
            QuoteBomAlternativeConfirmationGuardImpl
                ::normalizePath)
        .anyMatch(paths::contains);
  }

  private static boolean isIssueReachable(
      BomAlternativeGroupIssue issue,
      List<BomRawHierarchy> rows) {
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
    if (rows != null) {
      rows.stream()
          .map(BomRawHierarchy::getPath)
          .map(
              QuoteBomAlternativeConfirmationGuardImpl
                  ::normalizePath)
          .filter(StringUtils::hasText)
          .forEach(paths::add);
    }
    return paths;
  }

  private static int groupDepth(BomAlternativeGroup group) {
    return group.candidates().stream()
        .map(BomAlternativeCandidate::path)
        .map(
            QuoteBomAlternativeConfirmationGuardImpl
                ::normalizePath)
        .filter(StringUtils::hasText)
        .mapToInt(
            QuoteBomAlternativeConfirmationGuardImpl
                ::pathDepth)
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

  private static String normalizePath(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String path = value.trim().replace('\\', '/');
    while (path.contains("//")) {
      path = path.replace("//", "/");
    }
    if (!path.startsWith("/")) {
      path = "/" + path;
    }
    if (!path.endsWith("/")) {
      path = path + "/";
    }
    return path;
  }

  private static boolean sameText(String left, String right) {
    return StringUtils.hasText(left)
        && StringUtils.hasText(right)
        && left.trim().equalsIgnoreCase(right.trim());
  }

  private static String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String display(Object value) {
    return value == null ? "（空）" : value.toString();
  }

  private static QuoteIngestException stale(String message) {
    return new QuoteIngestException(
        ALT_SOURCE_STALE + "：" + message);
  }

  private record ReachableSnapshot(
      List<BomAlternativeGroup> groups,
      List<BomRawHierarchy> rows) {}
}
