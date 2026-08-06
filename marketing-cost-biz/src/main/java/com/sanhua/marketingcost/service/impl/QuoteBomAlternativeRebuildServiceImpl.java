package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeCandidate;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomChildType;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeRebuildService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionCommand;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationResult;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeWorkflowInvalidationService;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 标准/替代选择、当前报价产品重建和下游状态失效的事务编排。 */
@Service
public class QuoteBomAlternativeRebuildServiceImpl
    implements QuoteBomAlternativeRebuildService {

  public static final String BOM_ALREADY_CONFIRMED =
      "BOM_ALREADY_CONFIRMED";
  public static final String MANUAL_ROW_CHANGES_EXIST =
      "MANUAL_ROW_CHANGES_EXIST";

  private static final int ACTIVE = 1;
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";

  private final BomRawHierarchyMapper bomRawHierarchyMapper;
  private final BomAlternativeGroupResolver groupResolver;
  private final QuoteBomAlternativeSelectionService selectionService;
  private final QuoteBomPreparationRecordMapper preparationRecordMapper;
  private final BomCostingRowMapper costingRowMapper;
  private final QuoteBomConfirmationService confirmationService;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final QuoteBomAlternativeWorkflowInvalidationService
      workflowInvalidationService;

  public QuoteBomAlternativeRebuildServiceImpl(
      BomRawHierarchyMapper bomRawHierarchyMapper,
      BomAlternativeGroupResolver groupResolver,
      QuoteBomAlternativeSelectionService selectionService,
      QuoteBomPreparationRecordMapper preparationRecordMapper,
      BomCostingRowMapper costingRowMapper,
      QuoteBomConfirmationService confirmationService,
      QuoteProductBomCostingBuildService costingBuildService,
      QuoteBomAlternativeWorkflowInvalidationService
          workflowInvalidationService) {
    this.bomRawHierarchyMapper = bomRawHierarchyMapper;
    this.groupResolver = groupResolver;
    this.selectionService = selectionService;
    this.preparationRecordMapper = preparationRecordMapper;
    this.costingRowMapper = costingRowMapper;
    this.confirmationService = confirmationService;
    this.costingBuildService = costingBuildService;
    this.workflowInvalidationService = workflowInvalidationService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteBomAlternativeRebuildResult rebuild(
      QuoteBomAlternativeRebuildCommand command) {
    NormalizedCommand normalized = normalize(command);
    QuoteBomPreparationRecord preparation =
        requirePreparation(normalized);
    String costingProductCode =
        required(
            "quoteProductCode", preparation.getQuoteProductCode());
    BomAlternativeGroup group = requireGroup(normalized);
    BomAlternativeCandidate selectedCandidate =
        requireCandidate(
            group,
            normalized.selectedMaterialCode(),
            normalized.expectedBuildBatchId());
    QuoteBomAlternativeSelectionScope selectionScope =
        selectionScope(normalized);
    QuoteBomAlternativeSelectionResult current =
        selectionService.findCurrent(
            selectionScope, normalized.alternativeGroupKey());
    boolean exactRepeat =
        isExactRepeat(current, selectedCandidate);
    if (!exactRepeat && current != null) {
      validateExpectedVersion(
          current,
          normalized.expectedSelectionVersion(),
          group);
    }
    QuoteBomAlternativeSelectionCommand selectionCommand =
        selectionCommand(
            normalized,
            selectionScope,
            normalized.selectionRemark());

    if (exactRepeat) {
      QuoteBomAlternativeSelectionResult idempotent =
          selectionService.save(selectionCommand, group);
      return new QuoteBomAlternativeRebuildResult(
          idempotent,
          true,
          false,
          false,
          0,
          0,
          null,
          0,
          0,
          0);
    }

    if (confirmationService.hasActiveConfirmation(
        normalized.oaNo(),
        normalized.oaFormItemId(),
        costingProductCode,
        normalized.periodMonth())) {
      throw failure(
          BOM_ALREADY_CONFIRMED,
          "报价物料明细已确认；"
              + groupContext(group)
              + "；请先撤销报价物料明细确认后再切换");
    }

    List<BomCostingRow> beforeRows =
        loadCostingRows(
            normalized, costingProductCode);
    int manualCount =
        (int)
            beforeRows.stream()
                .filter(
                    row ->
                        Integer.valueOf(1)
                            .equals(row.getManualModified()))
                .count();
    if (manualCount > 0
        && !normalized.confirmDiscardManualChanges()) {
      throw failure(
          MANUAL_ROW_CHANGES_EXIST,
          "当前有"
              + manualCount
              + "条人工修改结算行；"
              + groupContext(group)
              + "；请确认清除人工修改后再切换BOM分支");
    }

    String remark =
        manualCount <= 0
            ? normalized.selectionRemark()
            : appendRemark(
                normalized.selectionRemark(),
                "用户已确认清除"
                    + manualCount
                    + "条人工修改结算行");
    selectionCommand =
        selectionCommand(
            normalized, selectionScope, remark);
    QuoteBomAlternativeSelectionResult saved =
        selectionService.save(selectionCommand, group);
    QuoteBomCostingBuildResponse build =
        costingBuildService.buildByOaFormItem(
            normalized.oaFormItemId(),
            normalized.periodMonth(),
            normalized.quoteDate());
    if (build == null) {
      throw new IllegalStateException("报价物料明细重建未返回结果");
    }
    QuoteBomAlternativeWorkflowInvalidationResult invalidated =
        workflowInvalidationService.invalidate(
            normalized.oaNo(),
            normalized.oaFormItemId(),
            costingProductCode,
            normalized.periodMonth());
    List<BomCostingRow> afterRows =
        loadCostingRows(
            normalized, costingProductCode);
    return new QuoteBomAlternativeRebuildResult(
        saved,
        false,
        true,
        manualCount > 0,
        beforeRows.size(),
        afterRows.size(),
        build.buildBatchId(),
        invalidated.priceTypeCount(),
        invalidated.pricePrepareCount(),
        invalidated.costRunCount());
  }

  private QuoteBomPreparationRecord requirePreparation(
      NormalizedCommand command) {
    QuoteBomPreparationRecord preparation =
        preparationRecordMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(
                    QuoteBomPreparationRecord::getOaFormItemId,
                    command.oaFormItemId())
                .eq(
                    QuoteBomPreparationRecord::getActiveFlag,
                    ACTIVE)
                .orderByDesc(
                    QuoteBomPreparationRecord::getUpdatedAt)
                .orderByDesc(QuoteBomPreparationRecord::getId)
                .last("LIMIT 1"));
    if (preparation == null) {
      throw failure(
          "ALT_QUOTE_SCOPE_INVALID",
          "当前报价产品没有有效的BOM准备记录");
    }
    if (!sameText(preparation.getOaNo(), command.oaNo())
        || !sameText(
            formalProductCode(preparation),
            command.topProductCode())
        || !sameText(
            preparation.getPriceOrgCode(),
            command.organization().priceOrgCode())
        || !sameText(
            preparation.getMaterialOrganizationCode(),
            command
                .organization()
                .materialOrganizationCode())) {
      throw failure(
          "ALT_QUOTE_SCOPE_INVALID",
          "报价、产品料号或组织与当前BOM准备记录不一致");
    }
    return preparation;
  }

  private BomAlternativeGroup requireGroup(
      NormalizedCommand command) {
    List<BomRawHierarchy> rows =
        bomRawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(
                    BomRawHierarchy::getPriceOrgCode,
                    command.organization().priceOrgCode())
                .eq(
                    BomRawHierarchy::getTopProductCode,
                    command.topProductCode())
                .eq(
                    BomRawHierarchy::getBomPurpose,
                    command.bomPurpose())
                .le(
                    BomRawHierarchy::getEffectiveFrom,
                    command.quoteDate())
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(
                                BomRawHierarchy::getEffectiveTo,
                                command.quoteDate()))
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    List<BomRawHierarchy> effectiveRows =
        BomEffectiveTreePruner.prune(
            rows == null ? List.of() : rows,
            command.topProductCode());
    BomAlternativeGroupResolution resolution =
        groupResolver.resolve(effectiveRows);
    for (BomAlternativeGroupIssue issue : resolution.issues()) {
      if (sameText(
          issue.alternativeGroupKey(),
          command.alternativeGroupKey())) {
        throw failure(
            issue.code(),
            issue.message()
                + "；父件="
                + display(issue.parentMaterialNo())
                + "；子项序号="
                + display(issue.childSeq())
                + "；标准件="
                + ("ALT_STANDARD_MISSING".equals(issue.code())
                    ? "（缺失）"
                    : "请检查组内标准行")
                + "；替代件="
                + display(issue.candidateMaterialCode())
                + "；BOM目的="
                + display(issue.bomPurpose())
                + "；BOM版本="
                + display(issue.bomVersion())
                + "；请维护U9 BOM后重新同步");
      }
    }
    return resolution.groups().stream()
        .filter(
            group ->
                sameText(
                    group.alternativeGroupKey(),
                    command.alternativeGroupKey()))
        .findFirst()
        .orElseThrow(
            () ->
                failure(
                    "ALT_GROUP_NOT_FOUND",
                    "替代组不存在或已变化；顶层产品="
                        + display(command.topProductCode())
                        + "；替代组="
                        + display(command.alternativeGroupKey())
                        + "；BOM目的="
                        + display(command.bomPurpose())
                        + "；请刷新BOM后重新选择"));
  }

  private BomAlternativeCandidate requireCandidate(
      BomAlternativeGroup group,
      String selectedMaterialCode,
      String expectedBuildBatchId) {
    BomAlternativeCandidate candidate =
        group.candidates().stream()
            .filter(
                item ->
                    sameText(
                        item.materialCode(),
                        selectedMaterialCode))
            .findFirst()
            .orElseThrow(
                () ->
                    failure(
                        "ALT_CANDIDATE_INVALID",
                        "所选料号"
                            + display(selectedMaterialCode)
                            + "不属于当前替代组；"
                            + groupContext(group)
                            + "；请从当前候选中重新选择"));
    if (StringUtils.hasText(expectedBuildBatchId)
        && !Objects.equals(
            expectedBuildBatchId.trim(),
            trimToNull(candidate.sourceBuildBatchId()))) {
      throw failure(
          "ALT_SOURCE_STALE",
          "BOM构建批次已变化；"
              + groupContext(group)
              + "；请刷新候选后重新选择");
    }
    return candidate;
  }

  private void validateExpectedVersion(
      QuoteBomAlternativeSelectionResult current,
      Integer expectedVersion,
      BomAlternativeGroup group) {
    int currentVersion =
        current == null || current.selectionVersion() == null
            ? 0
            : current.selectionVersion();
    if (expectedVersion == null
        || expectedVersion != currentVersion) {
      throw failure(
          "ALT_SELECTION_CONFLICT",
          "选择版本已变化，当前版本="
              + currentVersion
              + "，请求版本="
              + expectedVersion
              + "；"
              + groupContext(group)
              + "；请刷新后重试");
    }
  }

  private boolean isExactRepeat(
      QuoteBomAlternativeSelectionResult current,
      BomAlternativeCandidate selectedCandidate) {
    return current != null
        && selectedCandidate != null
        && sameText(
            current.selectedMaterialCode(),
            selectedCandidate.materialCode())
        && sameText(
            current.sourceImportBatchId(),
            selectedCandidate.sourceImportBatchId())
        && sameText(
            current.sourceBuildBatchId(),
            selectedCandidate.sourceBuildBatchId());
  }

  private List<BomCostingRow> loadCostingRows(
      NormalizedCommand command, String costingProductCode) {
    List<BomCostingRow> rows =
        costingRowMapper.selectQuoteCostingSnapshot(
            command.oaNo(),
            command.oaFormItemId(),
            costingProductCode,
            command.periodMonth());
    return rows == null ? List.of() : rows;
  }

  private QuoteBomAlternativeSelectionScope selectionScope(
      NormalizedCommand command) {
    return new QuoteBomAlternativeSelectionScope(
        command.oaNo(),
        command.oaFormItemId(),
        command.topProductCode(),
        command.periodMonth(),
        command.organization().priceOrgCode(),
        command.businessUnitType());
  }

  private QuoteBomAlternativeSelectionCommand selectionCommand(
      NormalizedCommand command,
      QuoteBomAlternativeSelectionScope scope,
      String remark) {
    return new QuoteBomAlternativeSelectionCommand(
        scope,
        command.alternativeGroupKey(),
        command.selectedMaterialCode(),
        command.expectedSelectionVersion(),
        command.expectedBuildBatchId(),
        command.selectedBy(),
        remark);
  }

  private NormalizedCommand normalize(
      QuoteBomAlternativeRebuildCommand command) {
    if (command == null) {
      throw new IllegalArgumentException("替代选择重建命令不能为空");
    }
    String periodMonth = required("periodMonth", command.periodMonth());
    try {
      periodMonth = YearMonth.parse(periodMonth).toString();
    } catch (DateTimeParseException ex) {
      throw new IllegalArgumentException(
          "periodMonth必须为YYYY-MM", ex);
    }
    if (command.oaFormItemId() == null
        || command.oaFormItemId() <= 0) {
      throw new IllegalArgumentException("oaFormItemId不能为空");
    }
    QuoteDataOrganization organization =
        MaterialOrganization.normalizeQuoteDataOrganization(
            new QuoteDataOrganization(
                command.priceOrgCode(),
                command.materialOrganizationCode()));
    return new NormalizedCommand(
        required("oaNo", command.oaNo()),
        command.oaFormItemId(),
        required("topProductCode", command.topProductCode()),
        periodMonth,
        organization,
        required("businessUnitType", command.businessUnitType()),
        firstText(command.bomPurpose(), "主制造"),
        command.quoteDate() == null
            ? LocalDate.now()
            : command.quoteDate(),
        required(
            "alternativeGroupKey",
            command.alternativeGroupKey()),
        required(
            "selectedMaterialCode",
            command.selectedMaterialCode()),
        command.expectedSelectionVersion(),
        trimToNull(command.expectedBuildBatchId()),
        command.confirmDiscardManualChanges(),
        trimToNull(command.selectedBy()),
        trimToNull(command.selectionRemark()));
  }

  private String formalProductCode(
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

  private static String appendRemark(
      String current, String addition) {
    String left = trimToNull(current);
    String right = trimToNull(addition);
    return left == null
        ? right
        : left + "；" + right;
  }

  private static String groupContext(BomAlternativeGroup group) {
    if (group == null || group.identity() == null) {
      return "替代组上下文不可用";
    }
    String standard =
        group.candidates().stream()
            .filter(
                candidate ->
                    candidate.childType() == BomChildType.STANDARD)
            .map(BomAlternativeCandidate::materialCode)
            .filter(StringUtils::hasText)
            .findFirst()
            .orElse("（缺失）");
    String alternatives =
        group.candidates().stream()
            .filter(
                candidate ->
                    candidate.childType() == BomChildType.ALTERNATIVE)
            .map(BomAlternativeCandidate::materialCode)
            .filter(StringUtils::hasText)
            .collect(Collectors.joining(","));
    return "父件="
        + display(group.identity().parentMaterialNo())
        + "；子项序号="
        + display(group.identity().childSeq())
        + "；标准件="
        + display(standard)
        + "；替代件="
        + display(alternatives)
        + "；BOM目的="
        + display(group.identity().bomPurpose())
        + "；BOM版本="
        + display(group.identity().bomVersion());
  }

  private static QuoteBomAlternativeSelectionException failure(
      String code, String message) {
    return new QuoteBomAlternativeSelectionException(
        code, code + ": " + message);
  }

  private static boolean sameText(
      String first, String second) {
    return Objects.equals(
        trimToNull(first), trimToNull(second));
  }

  private static String firstText(
      String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null
        ? trimToNull(second)
        : normalized;
  }

  private static String required(
      String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value)
        ? value.trim()
        : null;
  }

  private static String display(Object value) {
    return value == null || value.toString().isBlank()
        ? "（空）"
        : value.toString().trim();
  }

  private record NormalizedCommand(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String periodMonth,
      QuoteDataOrganization organization,
      String businessUnitType,
      String bomPurpose,
      LocalDate quoteDate,
      String alternativeGroupKey,
      String selectedMaterialCode,
      Integer expectedSelectionVersion,
      String expectedBuildBatchId,
      boolean confirmDiscardManualChanges,
      String selectedBy,
      String selectionRemark) {
  }
}
