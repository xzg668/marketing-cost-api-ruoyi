package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeCandidateResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeGroupResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionHistoryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionRequest;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSelectionResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomAlternativeSummaryResponse;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteBomAlternativeApplicationService;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneRequest;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeAuditService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionRepository;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 报价 BOM 标准/替代 API 应用服务。
 *
 * <p>Controller 只传递业务意图；本服务重新读取当前正式 BOM，建立权威候选，并通过
 * QBA-08 原子重建入口保存选择。
 */
@Service
public class QuoteBomAlternativeApplicationServiceImpl
    implements QuoteBomAlternativeApplicationService {

  private static final int ACTIVE = 1;
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String MAIN_BOM_PURPOSE = "主制造";
  private static final String ALT_QUOTE_SCOPE_INVALID =
      "ALT_QUOTE_SCOPE_INVALID";
  private static final String ALT_GROUP_NOT_FOUND =
      "ALT_GROUP_NOT_FOUND";
  private static final String ALT_SOURCE_STALE = "ALT_SOURCE_STALE";
  private static final String ALT_BRANCH_STRUCTURE_MISSING =
      "ALT_BRANCH_STRUCTURE_MISSING";

  private static final List<String> INVALIDATED_WORKFLOW =
      List.of(
          "QUOTE_BOM",
          "PRICE_TYPE",
          "PRICE_PREPARE",
          "FINAL_PRICE",
          "COST_RUN");

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final BomAlternativeGroupResolver groupResolver;
  private final BomAlternativeBranchPruner branchPruner;
  private final QuoteBomAlternativeSelectionService selectionService;
  private final QuoteBomAlternativeSelectionRepository selectionRepository;
  private final QuoteBomAlternativeRebuildService rebuildService;
  private final QuoteBomAlternativeAuditService auditService;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormMapper oaFormMapper;

  public QuoteBomAlternativeApplicationServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      BomAlternativeGroupResolver groupResolver,
      BomAlternativeBranchPruner branchPruner,
      QuoteBomAlternativeSelectionService selectionService,
      QuoteBomAlternativeSelectionRepository selectionRepository,
      QuoteBomAlternativeRebuildService rebuildService,
      QuoteBomAlternativeAuditService auditService,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      OaFormItemMapper oaFormItemMapper,
      OaFormMapper oaFormMapper) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.groupResolver = groupResolver;
    this.branchPruner = branchPruner;
    this.selectionService = selectionService;
    this.selectionRepository = selectionRepository;
    this.rebuildService = rebuildService;
    this.auditService = auditService;
    this.preparationRecordMapper = preparationRecordMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormMapper = oaFormMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeSummaryResponse getAlternativeGroups(
      String oaNo, Long oaFormItemId, String periodMonth) {
    ApiContext context =
        requireContext(oaNo, oaFormItemId, periodMonth);
    GroupSnapshot snapshot = loadReachableGroups(context);
    List<QuoteBomAlternativeSelectionResult> synchronizedSelections =
        selectionService.synchronize(
            context.selectionScope(), snapshot.groups());
    Map<String, QuoteBomAlternativeSelectionResult> selectionByGroup =
        synchronizedSelections.stream()
            .collect(
                Collectors.toMap(
                    QuoteBomAlternativeSelectionResult::alternativeGroupKey,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));

    List<QuoteBomAlternativeGroupResponse> groups =
        snapshot.groups().stream()
            .map(
                group ->
                    toGroupResponse(
                        group,
                        selectionByGroup.get(
                            group.alternativeGroupKey()),
                        snapshot.rows()))
            .toList();
    int manualAlternativeCount =
        (int)
            groups.stream()
                .filter(
                    group ->
                        QuoteBomAlternativeSelection
                            .SOURCE_MANUAL_ALTERNATIVE
                            .equals(group.selectionSource()))
                .count();
    boolean reviewRequired =
        groups.stream()
            .anyMatch(
                QuoteBomAlternativeGroupResponse::reviewRequired);
    return new QuoteBomAlternativeSummaryResponse(
        context.periodMonth(),
        groups.size(),
        manualAlternativeCount,
        reviewRequired,
        groups);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeSelectionResponse saveSelection(
      String oaNo,
      Long oaFormItemId,
      String alternativeGroupKey,
      QuoteBomAlternativeSelectionRequest request,
      String operator) {
    if (request == null) {
      throw new IllegalArgumentException("选择请求不能为空");
    }
    String groupKey =
        required("groupKey", alternativeGroupKey);
    ApiContext context =
        requireContext(
            oaNo, oaFormItemId, request.periodMonth());
    QuoteBomAlternativeSelection previous =
        selectionRepository.findCurrent(
            context.selectionScope(), groupKey);
    QuoteBomAlternativeRebuildResult rebuilt;
    try {
      rebuilt =
          rebuildService.rebuild(
              new QuoteBomAlternativeRebuildCommand(
                  context.oaNo(),
                  context.oaFormItemId(),
                  context.topProductCode(),
                  context.periodMonth(),
                  context.organization().priceOrgCode(),
                  context
                      .organization()
                      .materialOrganizationCode(),
                  context.businessUnitType(),
                  context.bomPurpose(),
                  context.quoteDate(),
                  groupKey,
                  required(
                      "selectedMaterialCode",
                      request.selectedMaterialCode()),
                  request.expectedSelectionVersion(),
                  trimToNull(request.expectedBuildBatchId()),
                  firstText(operator, "system"),
                  trimToNull(request.selectionRemark())));
    } catch (QuoteIngestException exception) {
      String message = trimToNull(exception.getMessage());
      if (message != null
          && message.contains(ALT_BRANCH_STRUCTURE_MISSING)) {
        throw failure(
            ALT_BRANCH_STRUCTURE_MISSING, message);
      }
      throw exception;
    }
    if (rebuilt == null || rebuilt.selection() == null) {
      throw new IllegalStateException("替代选择重建未返回选择结果");
    }
    QuoteBomAlternativeSelectionResult selection =
        rebuilt.selection();
    if (!rebuilt.idempotent()) {
      auditService.recordSelectionChange(
          context.selectionScope(),
          groupKey,
          previous,
          selection,
          firstText(operator, "system"),
          trimToNull(request.selectionRemark()));
    }
    return new QuoteBomAlternativeSelectionResponse(
        selection.alternativeGroupKey(),
        selection.selectionVersion(),
        selection.selectedMaterialCode(),
        selection.selectedChildType() == null
            ? null
            : selection.selectedChildType().name(),
        selection.selectionSource(),
        rebuilt.idempotent(),
        rebuilt.recalculationRequired(),
        rebuilt.idempotent() ? List.of() : INVALIDATED_WORKFLOW);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<QuoteBomAlternativeSelectionHistoryResponse>
      getSelectionHistory(
          String oaNo,
          Long oaFormItemId,
          String alternativeGroupKey,
          String periodMonth) {
    String groupKey =
        required("groupKey", alternativeGroupKey);
    ApiContext context =
        requireContext(oaNo, oaFormItemId, periodMonth);
    List<QuoteBomAlternativeSelection> history =
        selectionRepository.findHistory(
            context.selectionScope(), groupKey);
    if (history.isEmpty()) {
      QuoteBomAlternativeSummaryResponse summary =
          getAlternativeGroups(
              context.oaNo(),
              context.oaFormItemId(),
              context.periodMonth());
      boolean currentGroupExists =
          summary.groups().stream()
              .anyMatch(
                  group ->
                      sameText(
                          group.alternativeGroupKey(), groupKey));
      if (!currentGroupExists) {
        throw failure(
            ALT_GROUP_NOT_FOUND,
            "替代组不存在或已变化，请刷新后重新选择");
      }
      history =
          selectionRepository.findHistory(
              context.selectionScope(), groupKey);
    }
    return history.stream()
        .map(this::toHistoryResponse)
        .toList();
  }

  private GroupSnapshot loadReachableGroups(ApiContext context) {
    List<BomRawHierarchy> rows =
        loadEffectiveRows(context);
    if (rows.isEmpty()) {
      throw failure(
          ALT_SOURCE_STALE,
          "当前正式BOM不存在或有效期已变化；顶层产品="
              + display(context.topProductCode())
              + "；BOM目的="
              + display(context.bomPurpose())
              + "；请重新同步BOM后再查看标准/替代选择");
    }
    BomAlternativeGroupResolution resolution =
        groupResolver.resolve(rows);
    List<BomAlternativeGroup> orderedGroups =
        resolution.groups().stream()
            .sorted(
                Comparator.comparingInt(
                        QuoteBomAlternativeApplicationServiceImpl
                            ::groupDepth)
                    .thenComparing(
                        BomAlternativeGroup::alternativeGroupKey))
            .toList();
    List<BomRawHierarchy> currentRows = rows;
    List<BomAlternativeGroup> reachableGroups =
        new ArrayList<>();
    for (BomAlternativeGroup group : orderedGroups) {
      if (!isReachable(group, currentRows)) {
        continue;
      }
      QuoteBomAlternativeSelectionResult current =
          selectionService.findCurrent(
              context.selectionScope(),
              group.alternativeGroupKey());
      String selectedMaterialCode =
          selectedMaterialForReachability(group, current);
      currentRows =
          branchPruner
              .prune(
                  new BomAlternativePruneRequest(
                      currentRows,
                      List.of(group),
                      Map.of(
                          group.alternativeGroupKey(),
                          selectedMaterialCode)))
              .nodes();
      reachableGroups.add(group);
    }
    for (BomAlternativeGroupIssue issue : resolution.issues()) {
      if (isIssueReachable(issue, currentRows)) {
        throw issueFailure(issue);
      }
    }
    return new GroupSnapshot(
        List.copyOf(reachableGroups), rows);
  }

  private List<BomRawHierarchy> loadEffectiveRows(
      ApiContext context) {
    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(
                    BomRawHierarchy::getPriceOrgCode,
                    context.organization().priceOrgCode())
                .eq(
                    BomRawHierarchy::getTopProductCode,
                    context.topProductCode())
                .eq(
                    BomRawHierarchy::getBomPurpose,
                    context.bomPurpose())
                .le(
                    BomRawHierarchy::getEffectiveFrom,
                    context.quoteDate())
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(
                                BomRawHierarchy::getEffectiveTo,
                                context.quoteDate()))
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    return BomEffectiveTreePruner.prune(
        rows == null ? List.of() : rows,
        context.topProductCode());
  }

  private ApiContext requireContext(
      String oaNo, Long oaFormItemId, String periodMonth) {
    String normalizedOaNo = required("oaNo", oaNo);
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException(
          "oaFormItemId不能为空");
    }
    String normalizedMonth = normalizeMonth(periodMonth);
    QuoteBomPreparationRecord preparation =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(
                    QuoteBomPreparationRecord::getOaFormItemId,
                    oaFormItemId)
                .eq(
                    QuoteBomPreparationRecord::getActiveFlag,
                    ACTIVE)
                .orderByDesc(
                    QuoteBomPreparationRecord::getUpdatedAt)
                .orderByDesc(QuoteBomPreparationRecord::getId)
                .last("LIMIT 1"));
    if (preparation == null) {
      throw failure(
          ALT_QUOTE_SCOPE_INVALID,
          "当前报价产品没有有效的BOM准备记录");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    OaForm form =
        item == null || item.getOaFormId() == null
            ? null
            : oaFormMapper.selectById(item.getOaFormId());
    if (item == null
        || form == null
        || !Objects.equals(
            item.getOaFormId(), preparation.getOaFormId())
        || !sameText(preparation.getOaNo(), normalizedOaNo)
        || !sameText(form.getOaNo(), normalizedOaNo)) {
      throw failure(
          ALT_QUOTE_SCOPE_INVALID,
          "路径OA单号、产品行与当前报价BOM作用域不一致");
    }
    String businessUnitType =
        firstText(
            item.getBusinessUnitType(),
            firstText(
                form.getBusinessUnitType(),
                BusinessUnitContext.getCurrentBusinessUnitType()));
    if (!StringUtils.hasText(businessUnitType)) {
      throw failure(
          ALT_QUOTE_SCOPE_INVALID,
          "报价产品行缺少业务单元，不能读取替代选择");
    }
    String currentBusinessUnit =
        trimToNull(
            BusinessUnitContext.getCurrentBusinessUnitType());
    if (currentBusinessUnit != null
        && !BusinessUnitContext.isAdmin()
        && !sameText(currentBusinessUnit, businessUnitType)) {
      throw failure(
          ALT_QUOTE_SCOPE_INVALID,
          "当前登录业务单元不能访问该报价产品");
    }
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(
            new QuoteDataOrganization(
                preparation.getPriceOrgCode(),
                preparation.getMaterialOrganizationCode()));
    String topProductCode =
        required(
            "topProductCode",
            formalProductCode(preparation));
    LocalDate quoteDate = LocalDate.now();
    QuoteBomAlternativeSelectionScope selectionScope =
        new QuoteBomAlternativeSelectionScope(
            normalizedOaNo,
            oaFormItemId,
            topProductCode,
            normalizedMonth,
            organization.priceOrgCode(),
            businessUnitType.trim());
    return new ApiContext(
        normalizedOaNo,
        oaFormItemId,
        topProductCode,
        normalizedMonth,
        organization,
        businessUnitType.trim(),
        MAIN_BOM_PURPOSE,
        quoteDate,
        selectionScope);
  }

  private QuoteBomAlternativeGroupResponse toGroupResponse(
      BomAlternativeGroup group,
      QuoteBomAlternativeSelectionResult selection,
      List<BomRawHierarchy> rows) {
    if (selection == null) {
      throw failure(
          ALT_SOURCE_STALE,
          groupContext(group)
              + "；当前替代组没有可用选择，请刷新后重新确认");
    }
    String selectedMaterialCode =
        selection.selectedMaterialCode();
    String parentPath =
        parentPath(group.standardCandidate().path());
    List<QuoteBomAlternativeCandidateResponse> candidates =
        group.candidates().stream()
            .map(
                candidate ->
                    new QuoteBomAlternativeCandidateResponse(
                        candidate.materialCode(),
                        candidate.materialName(),
                        candidate.materialSpec(),
                        candidate.childType().name(),
                        candidate.qtyPerParent(),
                        candidate.sourceImportBatchId(),
                        candidate.sourceBuildBatchId(),
                        sameText(
                            candidate.materialCode(),
                            selectedMaterialCode)))
            .toList();
    return new QuoteBomAlternativeGroupResponse(
        group.alternativeGroupKey(),
        group.identity().parentMaterialNo(),
        parentMaterialName(
            rows,
            group.identity().parentMaterialNo(),
            parentPath),
        parentPath,
        group.identity().childSeq(),
        group.identity().processSeq(),
        group.identity().bomPurpose(),
        group.identity().bomVersion(),
        selection.selectionVersion(),
        selection.selectionSource(),
        selection.selectionStatus(),
        selectedMaterialCode,
        selection.selectedChildType() == null
            ? null
            : selection.selectedChildType().name(),
        selection.sourceBuildBatchId(),
        selection.reviewRequired(),
        selection.persisted(),
        candidates);
  }

  private QuoteBomAlternativeSelectionHistoryResponse toHistoryResponse(
      QuoteBomAlternativeSelection row) {
    boolean stale =
        QuoteBomAlternativeSelection.STATUS_STALE.equals(
            row.getSelectionStatus());
    return new QuoteBomAlternativeSelectionHistoryResponse(
        row.getSelectionNo(),
        row.getAlternativeGroupKey(),
        row.getSelectionVersion(),
        row.getStandardMaterialCode(),
        row.getSelectedMaterialCode(),
        row.getSelectedChildType(),
        row.getSelectionSource(),
        row.getSelectionStatus(),
        row.getSelectedBy(),
        row.getSelectedAt(),
        row.getSelectionRemark(),
        row.getCandidateSnapshotJson(),
        row.getSourceImportBatchId(),
        row.getSourceBuildBatchId(),
        stale);
  }

  private static String selectedMaterialForReachability(
      BomAlternativeGroup group,
      QuoteBomAlternativeSelectionResult current) {
    if (current != null
        && !current.reviewRequired()
        && current.persisted()
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

  private static String parentMaterialName(
      List<BomRawHierarchy> rows,
      String parentMaterialCode,
      String parentPath) {
    if (rows == null) {
      return null;
    }
    return rows.stream()
        .filter(
            row ->
                sameText(
                        row.getMaterialCode(),
                        parentMaterialCode)
                    && normalizePath(row.getPath())
                        .equals(normalizePath(parentPath)))
        .map(BomRawHierarchy::getMaterialName)
        .filter(StringUtils::hasText)
        .map(String::trim)
        .findFirst()
        .orElse(null);
  }

  private static QuoteBomAlternativeSelectionException issueFailure(
      BomAlternativeGroupIssue issue) {
    String message =
        issue.message()
            + "；父件="
            + display(issue.parentMaterialNo())
            + "；子项序号="
            + display(issue.childSeq())
            + "；标准件="
            + (BomAlternativeGroupIssue.ALT_STANDARD_MISSING.equals(
                    issue.code())
                ? "（缺失）"
                : "请检查组内标准行")
            + "；替代件="
            + display(issue.candidateMaterialCode())
            + "；BOM目的="
            + display(issue.bomPurpose())
            + "；BOM版本="
            + display(issue.bomVersion())
            + "；请维护U9 BOM后重新同步";
    return failure(issue.code(), message);
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

  private static boolean isReachable(
      BomAlternativeGroup group, List<BomRawHierarchy> rows) {
    Set<String> currentPaths = paths(rows);
    return group.candidates().stream()
        .map(BomAlternativeCandidate::path)
        .map(
            QuoteBomAlternativeApplicationServiceImpl
                ::normalizePath)
        .anyMatch(currentPaths::contains);
  }

  private static Set<String> paths(List<BomRawHierarchy> rows) {
    LinkedHashSet<String> paths = new LinkedHashSet<>();
    if (rows == null) {
      return paths;
    }
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
        .map(
            QuoteBomAlternativeApplicationServiceImpl
                ::normalizePath)
        .filter(StringUtils::hasText)
        .mapToInt(
            QuoteBomAlternativeApplicationServiceImpl
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

  private static String groupContext(BomAlternativeGroup group) {
    String standard =
        group == null
            ? null
            : group.standardCandidate().materialCode();
    String alternatives =
        group == null
            ? null
            : group.alternativeCandidates().stream()
                .map(BomAlternativeCandidate::materialCode)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(","));
    return "父件="
        + display(
            group == null
                ? null
                : group.identity().parentMaterialNo())
        + "；子项序号="
        + display(
            group == null ? null : group.identity().childSeq())
        + "；标准件="
        + display(standard)
        + "；替代件="
        + display(alternatives)
        + "；BOM目的="
        + display(
            group == null ? null : group.identity().bomPurpose())
        + "；BOM版本="
        + display(
            group == null ? null : group.identity().bomVersion());
  }

  private static String formalProductCode(
      QuoteBomPreparationRecord record) {
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return trimToNull(record.getQuoteProductCode());
    }
    return firstText(
        firstText(
            record.getSourceTopProductCode(),
            record.getReferenceFinishedCode()),
        record.getQuoteProductCode());
  }

  private static String normalizeMonth(String periodMonth) {
    String value = required("periodMonth", periodMonth);
    try {
      return YearMonth.parse(value).toString();
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          "periodMonth必须为YYYY-MM", exception);
    }
  }

  private static String parentPath(String childPath) {
    String normalized = normalizePath(childPath);
    if (normalized.isEmpty() || "/".equals(normalized)) {
      return normalized;
    }
    String withoutTrailing =
        normalized.substring(0, normalized.length() - 1);
    int lastSlash = withoutTrailing.lastIndexOf('/');
    return lastSlash < 0
        ? "/"
        : normalized.substring(0, lastSlash + 1);
  }

  private static String normalizePath(String path) {
    if (!StringUtils.hasText(path)) {
      return "";
    }
    String value =
        path.trim().replace('\\', '/').replaceAll("/+", "/");
    if (!value.startsWith("/")) {
      value = "/" + value;
    }
    if (!value.endsWith("/")) {
      value += "/";
    }
    return value.toUpperCase(Locale.ROOT);
  }

  private static boolean sameText(String first, String second) {
    return Objects.equals(
        normalizedText(first), normalizedText(second));
  }

  private static String normalizedText(String value) {
    return StringUtils.hasText(value)
        ? value.trim().toUpperCase(Locale.ROOT)
        : null;
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private static String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static String display(Object value) {
    return value == null || value.toString().isBlank()
        ? "（空）"
        : value.toString().trim();
  }

  private static QuoteBomAlternativeSelectionException failure(
      String code, String message) {
    return new QuoteBomAlternativeSelectionException(code, message);
  }

  private record ApiContext(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth,
      QuoteDataOrganization organization,
      String businessUnitType,
      String bomPurpose,
      LocalDate quoteDate,
      QuoteBomAlternativeSelectionScope selectionScope) {
  }

  private record GroupSnapshot(
      List<BomAlternativeGroup> groups,
      List<BomRawHierarchy> rows) {
  }
}
