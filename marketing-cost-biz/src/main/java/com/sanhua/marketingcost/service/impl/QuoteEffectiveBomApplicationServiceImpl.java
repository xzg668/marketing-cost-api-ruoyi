package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.QuoteDataOrganization;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomAlternativeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomExclusionSummaryResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomIssueResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomNodeResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteEffectiveBomResponse;
import com.sanhua.marketingcost.entity.BomRawHierarchy;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import com.sanhua.marketingcost.entity.QuoteBomMonthlySnapshot;
import com.sanhua.marketingcost.entity.QuoteBomPreparationRecord;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteEffectiveBomNode;
import com.sanhua.marketingcost.enums.MaterialOrganization;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.mapper.BomRawHierarchyMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomMonthlySnapshotMapper;
import com.sanhua.marketingcost.mapper.QuoteBomPreparationRecordMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.QuoteBomConfirmationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomApplicationService;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeBranchPruner;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroup;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupIssue;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolution;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativeGroupResolver;
import com.sanhua.marketingcost.service.bomalternative.BomAlternativePruneRequest;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionException;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionRepository;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionScope;
import com.sanhua.marketingcost.service.bomalternative.QuoteBomAlternativeSelectionService;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomBlockIssue;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomBuildRequest;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomBuildResult;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomExclusion;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomNodeDraft;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomShapeDecision;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantInput;
import com.sanhua.marketingcost.service.effectivebom.EffectiveBomVariantHasher;
import com.sanhua.marketingcost.service.effectivebom.QuoteBomMonthlyFreezeKey;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomBuilder;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomConfirmationCandidate;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomQueryException;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomRepository;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteBomContext;
import com.sanhua.marketingcost.service.ingest.QuoteBomContextResolver;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeRequest;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolution;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeResolver;
import com.sanhua.marketingcost.service.materialshape.MaterialQuoteShapeSource;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioResolution;
import com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeResolver;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

/** QEB-11：只处理一个 OA 产品行，生成本次计价 BOM 预览或读取本月已确定结果。 */
@Service
public class QuoteEffectiveBomApplicationServiceImpl
    implements QuoteEffectiveBomApplicationService {

  private static final int ACTIVE = 1;
  private static final int MAX_DEPTH = 128;
  private static final String PRODUCT_TYPE_NON_BARE = "NON_BARE";
  private static final String DEFAULT_BOM_PURPOSE = "主制造";
  private static final String FROZEN = "FROZEN";

  private final QuoteBomPreparationRecordMapper preparationMapper;
  private final QuoteBomStatusMapper statusMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final OaFormMapper oaFormMapper;
  private final QuoteBomMonthlySnapshotMapper monthlySnapshotMapper;
  private final BomRawHierarchyMapper rawHierarchyMapper;
  private final QuoteEffectiveBomRepository effectiveBomRepository;
  private final BomAlternativeGroupResolver alternativeGroupResolver;
  private final BomAlternativeBranchPruner alternativeBranchPruner;
  private final QuoteBomAlternativeSelectionRepository selectionRepository;
  private final QuoteBomAlternativeSelectionService selectionService;
  private final MaterialQuoteShapeResolver materialShapeResolver;
  private final SupplierRatioShapeResolver supplierRatioShapeResolver;
  private final QuoteEffectiveBomBuilder effectiveBomBuilder;
  private final EffectiveBomVariantHasher effectiveBomVariantHasher;
  private final QuoteBomContextResolver contextResolver;
  private final QuoteBomStatusService quoteBomStatusService;
  private final QuoteBomConfirmationService confirmationService;

  public QuoteEffectiveBomApplicationServiceImpl(
      QuoteBomPreparationRecordMapper preparationMapper,
      QuoteBomStatusMapper statusMapper,
      OaFormItemMapper oaFormItemMapper,
      OaFormMapper oaFormMapper,
      QuoteBomMonthlySnapshotMapper monthlySnapshotMapper,
      BomRawHierarchyMapper rawHierarchyMapper,
      QuoteEffectiveBomRepository effectiveBomRepository,
      BomAlternativeGroupResolver alternativeGroupResolver,
      BomAlternativeBranchPruner alternativeBranchPruner,
      QuoteBomAlternativeSelectionRepository selectionRepository,
      QuoteBomAlternativeSelectionService selectionService,
      MaterialQuoteShapeResolver materialShapeResolver,
      SupplierRatioShapeResolver supplierRatioShapeResolver,
      QuoteEffectiveBomBuilder effectiveBomBuilder,
      EffectiveBomVariantHasher effectiveBomVariantHasher,
      QuoteBomContextResolver contextResolver,
      QuoteBomStatusService quoteBomStatusService,
      QuoteBomConfirmationService confirmationService) {
    this.preparationMapper = preparationMapper;
    this.statusMapper = statusMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.oaFormMapper = oaFormMapper;
    this.monthlySnapshotMapper = monthlySnapshotMapper;
    this.rawHierarchyMapper = rawHierarchyMapper;
    this.effectiveBomRepository = effectiveBomRepository;
    this.alternativeGroupResolver = alternativeGroupResolver;
    this.alternativeBranchPruner = alternativeBranchPruner;
    this.selectionRepository = selectionRepository;
    this.selectionService = selectionService;
    this.materialShapeResolver = materialShapeResolver;
    this.supplierRatioShapeResolver = supplierRatioShapeResolver;
    this.effectiveBomBuilder = effectiveBomBuilder;
    this.effectiveBomVariantHasher = effectiveBomVariantHasher;
    this.contextResolver = contextResolver;
    this.quoteBomStatusService = quoteBomStatusService;
    this.confirmationService = confirmationService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteEffectiveBomResponse getEffectiveBom(String oaNo, Long oaFormItemId) {
    QueryContext context = requireContext(oaNo, oaFormItemId);
    QuoteBomMonthlySnapshot snapshot = findOrPrepareMonthlySnapshot(context);
    if (isFinalFrozen(context, snapshot)) {
      return frozenResponse(context, snapshot);
    }
    return draftResponse(context, snapshot);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteEffectiveBomResponse rebuildPreview(String oaNo, Long oaFormItemId) {
    QueryContext context = requireContext(oaNo, oaFormItemId);
    QuoteBomMonthlySnapshot snapshot = findOrPrepareMonthlySnapshot(context);
    if (isFinalFrozen(context, snapshot)) {
      throw failure(
          "EFFECTIVE_BOM_FROZEN",
          "报价物料明细已经确认，只能查看当前计价方案");
    }
    return draftResponse(context, snapshot);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteEffectiveBomResponse previewAlternative(
      String oaNo,
      Long oaFormItemId,
      String periodMonth,
      String alternativeGroupKey,
      String selectedMaterialCode) {
    QueryContext context = requireContext(oaNo, oaFormItemId);
    String requestedMonth = required(periodMonth, "periodMonth");
    if (!sameText(requestedMonth, context.costPeriodMonth())) {
      throw failure(
          "EFFECTIVE_BOM_PERIOD_MISMATCH",
          "预览月份与当前核算月份不一致，当前月份=" + context.costPeriodMonth());
    }
    QuoteBomMonthlySnapshot snapshot = findOrPrepareMonthlySnapshot(context);
    if (isFinalFrozen(context, snapshot)) {
      throw failure(
          "EFFECTIVE_BOM_FROZEN",
          "报价物料明细已经确认，不能预览其他标准/替代方案");
    }
    Map<String, String> overrides =
        Map.of(
            required(alternativeGroupKey, "alternativeGroupKey"),
            required(selectedMaterialCode, "selectedMaterialCode"));
    return evaluateDraft(context, snapshot, overrides).response();
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteEffectiveBomConfirmationCandidate prepareConfirmation(
      String oaNo, Long oaFormItemId) {
    QueryContext context = requireContext(oaNo, oaFormItemId);
    QuoteBomMonthlySnapshot snapshot = findOrPrepareMonthlySnapshot(context);
    if (isFinalFrozen(context, snapshot)) {
      QuoteEffectiveBomResponse response = frozenResponse(context, snapshot);
      return new QuoteEffectiveBomConfirmationCandidate(
          response, monthlyKey(context), selectionIds(response), null);
    }
    DraftEvaluation evaluation = evaluateDraft(context, snapshot);
    if (readyForConfirmation(evaluation)
        && evaluation.response().alternativeSelections().stream()
            .anyMatch(selection -> selection.selectionId() == null)) {
      RawSnapshot rawSnapshot = loadRawSnapshot(context, snapshot);
      BomAlternativeGroupResolution groups =
          alternativeGroupResolver.resolve(rawSnapshot.rows());
      selectionService.synchronize(
          selectionScope(context), groups.groups());
      evaluation = evaluateDraft(context, snapshot);
    }
    if (!"DRAFT".equals(evaluation.response().state())
        || evaluation.variantInput() == null) {
      String detail =
          evaluation.response().blockIssues().isEmpty()
              ? "当前最终BOM尚未就绪"
              : evaluation.response().blockIssues().getFirst().message();
      throw failure("EFFECTIVE_BOM_BLOCKED", detail);
    }
    return new QuoteEffectiveBomConfirmationCandidate(
        evaluation.response(),
        monthlyKey(context),
        evaluation.alternativeSelectionIds(),
        evaluation.variantInput());
  }

  private QuoteEffectiveBomResponse draftResponse(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    return evaluateDraft(context, snapshot).response();
  }

  private DraftEvaluation evaluateDraft(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    return evaluateDraft(context, snapshot, Map.of());
  }

  private DraftEvaluation evaluateDraft(
      QueryContext context,
      QuoteBomMonthlySnapshot snapshot,
      Map<String, String> selectionOverrides) {
    List<String> warnings = baseWarnings(context);
    if (snapshot == null) {
      return blockedEvaluation(
          blockedResponse(
              context,
              null,
              List.of(
                  new QuoteEffectiveBomIssueResponse(
                      "MONTHLY_BOM_NOT_READY",
                      context.topProductCode(),
                      null,
                      "系统未能从已有原始BOM生成本次计价BOM，请联系管理员检查数据")),
              warnings));
    }

    RawSnapshot rawSnapshot = loadRawSnapshot(context, snapshot);
    warnings.addAll(rawSnapshot.warnings());
    if (rawSnapshot.rows().isEmpty()) {
      return blockedEvaluation(
          blockedResponse(
              context,
              snapshot,
              List.of(
                  new QuoteEffectiveBomIssueResponse(
                      "MONTHLY_BOM_SOURCE_MISSING",
                      context.topProductCode(),
                      null,
                      "月度卡片指向的原始BOM层级不存在，来源批次="
                          + display(snapshot.getBomBatchId()))),
              warnings));
    }

    BomAlternativeGroupResolution groupResolution =
        alternativeGroupResolver.resolve(rawSnapshot.rows());
    SelectionSnapshot selections =
        resolveSelections(context, groupResolution.groups(), selectionOverrides);
    List<QuoteEffectiveBomIssueResponse> structureIssues =
        new ArrayList<>(groupResolution.issues().stream().map(this::toIssue).toList());
    structureIssues.addAll(selections.issues());
    if (!structureIssues.isEmpty()) {
      return blockedEvaluation(
          response(
              "BLOCKED",
              context,
              snapshot,
              rawSnapshot.sourceBuildBatchId(),
              null,
              null,
              List.of(),
              selections.responses(),
              new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
              structureIssues,
              warnings));
    }

    List<BomRawHierarchy> selectedRows;
    try {
      selectedRows =
          alternativeBranchPruner
              .prune(
                  new BomAlternativePruneRequest(
                      rawSnapshot.rows(),
                      groupResolution.groups(),
                      selections.selectedMaterialByGroup()))
              .nodes();
    } catch (QuoteBomAlternativeSelectionException exception) {
      return blockedEvaluation(
          response(
              "BLOCKED",
              context,
              snapshot,
              rawSnapshot.sourceBuildBatchId(),
              null,
              null,
              List.of(),
              selections.responses(),
              new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
              List.of(
                  new QuoteEffectiveBomIssueResponse(
                      exception.getCode(), null, null, exception.getMessage())),
              warnings));
    }

    Map<String, EffectiveBomShapeDecision> shapeDecisions =
        resolveShapes(context, selectedRows);
    EffectiveBomBuildResult built =
        effectiveBomBuilder.build(
            new EffectiveBomBuildRequest(
                rawSnapshot.rows(),
                groupResolution.groups(),
                selections.selectedMaterialByGroup(),
                shapeDecisions,
                MAX_DEPTH));
    warnings.addAll(built.warnings());

    Map<String, QuoteEffectiveBomAlternativeResponse> selectionByGroup =
        selections.responses().stream()
            .collect(
                Collectors.toMap(
                    QuoteEffectiveBomAlternativeResponse::alternativeGroupKey,
                    Function.identity(),
                    (first, ignored) -> first,
                    LinkedHashMap::new));
    List<QuoteEffectiveBomNodeResponse> nodes =
        built.nodes().stream()
            .map(node -> toNode(node, selectionByGroup.get(node.alternativeGroupKey())))
            .toList();
    if (built.blocked()) {
      return blockedEvaluation(
          response(
              "BLOCKED",
              context,
              snapshot,
              rawSnapshot.sourceBuildBatchId(),
              null,
              null,
              nodes,
              selections.responses(),
              exclusionSummary(built.exclusions()),
              built.blockIssues().stream().map(this::toIssue).toList(),
              warnings));
    }
    EffectiveBomVariantInput variant =
        new EffectiveBomVariantInput(
            context.costPeriodMonth(),
            required(snapshot.getBomBatchId(), "sourceBomBatchId"),
            context.organization().priceOrgCode(),
            context.topProductCode(),
            context.packageMethod(),
            selections.selectedMaterialByGroup(),
            built);
    String currentVariantHash = effectiveBomVariantHasher.hash(variant);
    String stagedBuildBatchId = trimToNull(snapshot.getEffectiveBuildBatchId());
    String stagedVariantHash = trimToNull(snapshot.getEffectiveVariantHash());
    boolean stagedVariantIsCurrent =
        stagedBuildBatchId != null && Objects.equals(stagedVariantHash, currentVariantHash);
    QuoteEffectiveBomResponse response =
        response(
            "DRAFT",
            context,
            snapshot,
            rawSnapshot.sourceBuildBatchId(),
            stagedVariantIsCurrent ? stagedBuildBatchId : null,
            stagedVariantIsCurrent ? stagedVariantHash : null,
            nodes,
            selections.responses(),
            exclusionSummary(built.exclusions()),
            List.of(),
            warnings);
    return new DraftEvaluation(response, variant, selectionIds(selections.responses()));
  }

  private QuoteEffectiveBomResponse frozenResponse(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    String buildBatchId = trimToNull(snapshot.getEffectiveBuildBatchId());
    if (buildBatchId == null) {
      throw failure(
          "EFFECTIVE_BOM_FROZEN_INVALID", "冻结月度卡片缺少最终构建编号，请联系管理员处理");
    }
    List<QuoteEffectiveBomNode> nodes =
        effectiveBomRepository.findNodesByBuildBatchId(buildBatchId);
    if (nodes == null || nodes.isEmpty()) {
      throw failure(
          "EFFECTIVE_BOM_FROZEN_INVALID",
          "冻结月度卡片指向的最终BOM不存在，构建编号=" + buildBatchId);
    }
    validateFrozenNodes(context, snapshot, buildBatchId, nodes);

    List<String> warnings = baseWarnings(context);
    if (isConfirmed(context)) {
      warnings.add("已确认版本只展示实际进入计价的节点，不按实时规则反推历史排除原因");
    }
    List<QuoteEffectiveBomAlternativeResponse> selections = frozenSelections(nodes);
    String state = frozenState(context, snapshot);
    return response(
        state,
        context,
        snapshot,
        trimToNull(snapshot.getBomBatchId()),
        buildBatchId,
        trimToNull(snapshot.getEffectiveVariantHash()),
        nodes.stream().map(this::toNode).toList(),
        selections,
        new QuoteEffectiveBomExclusionSummaryResponse(false, null, Map.of()),
        List.of(),
        warnings);
  }

  private QueryContext requireContext(String oaNo, Long oaFormItemId) {
    String normalizedOaNo = required(oaNo, "oaNo");
    if (oaFormItemId == null || oaFormItemId <= 0) {
      throw new IllegalArgumentException("oaFormItemId不能为空");
    }
    QuoteBomPreparationRecord preparation =
        preparationMapper.selectOne(
            Wrappers.<QuoteBomPreparationRecord>lambdaQuery()
                .eq(QuoteBomPreparationRecord::getOaFormItemId, oaFormItemId)
                .eq(QuoteBomPreparationRecord::getActiveFlag, ACTIVE)
                .orderByDesc(QuoteBomPreparationRecord::getUpdatedAt)
                .orderByDesc(QuoteBomPreparationRecord::getId)
                .last("LIMIT 1"));
    if (preparation == null) {
      throw failure(
          "EFFECTIVE_BOM_NOT_FOUND", "当前报价产品没有有效的BOM准备记录");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    OaForm form =
        item == null || item.getOaFormId() == null
            ? null
            : oaFormMapper.selectById(item.getOaFormId());
    if (item == null
        || form == null
        || !Objects.equals(item.getOaFormId(), preparation.getOaFormId())
        || !sameText(normalizedOaNo, preparation.getOaNo())
        || !sameText(normalizedOaNo, form.getOaNo())) {
      throw failure(
          "EFFECTIVE_BOM_SCOPE_INVALID", "路径OA单号、产品行与BOM准备记录不属于同一报价范围");
    }

    String businessUnit =
        firstText(
            item.getBusinessUnitType(),
            firstText(form.getBusinessUnitType(), BusinessUnitContext.getCurrentBusinessUnitType()));
    if (businessUnit == null) {
      throw failure("EFFECTIVE_BOM_SCOPE_INVALID", "报价产品行缺少业务单元");
    }
    String loginBusinessUnit = trimToNull(BusinessUnitContext.getCurrentBusinessUnitType());
    if (loginBusinessUnit != null
        && !BusinessUnitContext.isAdmin()
        && !sameText(loginBusinessUnit, businessUnit)) {
      throw failure("EFFECTIVE_BOM_SCOPE_INVALID", "当前登录业务单元不能访问该报价产品");
    }

    QuoteBomContext bomContext;
    try {
      bomContext =
          contextResolver.resolveWithExistingCostPeriod(
              form, item, preparation.getCostPeriodMonth());
    } catch (QuoteIngestException exception) {
      throw failure("EFFECTIVE_BOM_SCOPE_INVALID", exception.getMessage());
    }
    QuoteDataOrganization preparedOrganization;
    try {
      preparedOrganization =
          MaterialOrganization.normalizeQuoteDataOrganization(
              new QuoteDataOrganization(
                  preparation.getPriceOrgCode(),
                  preparation.getMaterialOrganizationCode()));
    } catch (IllegalArgumentException exception) {
      throw failure("EFFECTIVE_BOM_SCOPE_INVALID", exception.getMessage());
    }
    if (!sameText(
            preparedOrganization.priceOrgCode(), bomContext.organization().priceOrgCode())
        || !sameText(
            preparedOrganization.materialOrganizationCode(),
            bomContext.organization().materialOrganizationCode())) {
      throw failure(
          "EFFECTIVE_BOM_SCOPE_INVALID", "OA解析组织与BOM准备记录组织不一致，已阻断读取");
    }
    String topProductCode = required(formalProductCode(preparation), "topProductCode");
    return new QueryContext(
        normalizedOaNo,
        oaFormItemId,
        topProductCode,
        bomContext.costPeriodMonth(),
        bomContext.customerKey(),
        bomContext.customer().source().name(),
        bomContext.customer().warning(),
        bomContext.packageMethod(),
        preparedOrganization,
        businessUnit.trim());
  }

  private QuoteBomMonthlySnapshot findMonthlySnapshot(QueryContext context) {
    List<QuoteBomMonthlySnapshot> rows =
        monthlySnapshotMapper.selectList(
            Wrappers.<QuoteBomMonthlySnapshot>lambdaQuery()
                .eq(QuoteBomMonthlySnapshot::getCostPeriodMonth, context.costPeriodMonth())
                .eq(QuoteBomMonthlySnapshot::getProductCode, context.topProductCode())
                .eq(QuoteBomMonthlySnapshot::getCustomerCode, context.customerKey())
                .eq(QuoteBomMonthlySnapshot::getPackageMethod, context.packageMethod())
                .eq(
                    QuoteBomMonthlySnapshot::getPriceOrgCode,
                    context.organization().priceOrgCode())
                .eq(QuoteBomMonthlySnapshot::getSyncStatus, "SUCCESS")
                .eq(QuoteBomMonthlySnapshot::getActiveFlag, ACTIVE)
                .orderByDesc(QuoteBomMonthlySnapshot::getSyncAt)
                .orderByDesc(QuoteBomMonthlySnapshot::getId)
                .last("LIMIT 1"));
    return rows == null || rows.isEmpty() ? null : rows.getFirst();
  }

  /**
   * 历史报价可能已有原始 BOM，但尚未建立新流程使用的内部月度关系。页面打开或刷新时只为当前
   * 产品补齐这层技术关系，业务无需返回产品列表再次执行 BOM 同步。
   */
  private QuoteBomMonthlySnapshot findOrPrepareMonthlySnapshot(QueryContext context) {
    QuoteBomMonthlySnapshot snapshot = findMonthlySnapshot(context);
    if (snapshot != null) {
      return snapshot;
    }
    quoteBomStatusService.checkItemForCostRun(
        context.oaNo(), context.oaFormItemId(), context.costPeriodMonth());
    return findMonthlySnapshot(context);
  }

  private RawSnapshot loadRawSnapshot(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    String batchId = trimToNull(snapshot.getBomBatchId());
    if (batchId == null) {
      return new RawSnapshot(List.of(), null, List.of("月度卡片缺少原始BOM来源批次"));
    }
    LocalDate asOfDate =
        snapshot.getSyncAt() == null
            ? YearMonth.parse(context.costPeriodMonth()).atDay(1)
            : snapshot.getSyncAt().toLocalDate();
    List<BomRawHierarchy> candidates =
        rawHierarchyMapper.selectList(
            Wrappers.<BomRawHierarchy>lambdaQuery()
                .eq(BomRawHierarchy::getPriceOrgCode, context.organization().priceOrgCode())
                .eq(BomRawHierarchy::getTopProductCode, context.topProductCode())
                .eq(
                    BomRawHierarchy::getBomPurpose,
                    firstText(snapshot.getBomPurpose(), DEFAULT_BOM_PURPOSE))
                .and(
                    wrapper ->
                        wrapper
                            .eq(BomRawHierarchy::getBuildBatchId, batchId)
                            .or()
                            .eq(BomRawHierarchy::getSourceImportBatchId, batchId))
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveFrom)
                            .or()
                            .le(BomRawHierarchy::getEffectiveFrom, asOfDate))
                .and(
                    wrapper ->
                        wrapper
                            .isNull(BomRawHierarchy::getEffectiveTo)
                            .or()
                            .ge(BomRawHierarchy::getEffectiveTo, asOfDate))
                .orderByAsc(BomRawHierarchy::getLevel)
                .orderByAsc(BomRawHierarchy::getPath)
                .orderByAsc(BomRawHierarchy::getSortSeq)
                .orderByAsc(BomRawHierarchy::getId));
    if (candidates == null || candidates.isEmpty()) {
      return new RawSnapshot(List.of(), batchId, List.of());
    }

    List<String> warnings = new ArrayList<>();
    List<BomRawHierarchy> exact =
        candidates.stream().filter(row -> sameText(batchId, row.getBuildBatchId())).toList();
    List<BomRawHierarchy> selected = exact;
    String sourceBuildBatchId = batchId;
    if (exact.isEmpty()) {
      sourceBuildBatchId = latestSourceBuildBatchId(candidates);
      String selectedBatch = sourceBuildBatchId;
      selected =
          candidates.stream()
              .filter(row -> sameText(selectedBatch, row.getBuildBatchId()))
              .toList();
      warnings.add(
          "月度卡片保存的是U9导入批次，已定位到对应层级构建批次="
              + display(sourceBuildBatchId));
    }
    return new RawSnapshot(
        BomEffectiveTreePruner.prune(selected, context.topProductCode()),
        sourceBuildBatchId,
        warnings);
  }

  private SelectionSnapshot resolveSelections(
      QueryContext context, List<BomAlternativeGroup> groups) {
    return resolveSelections(context, groups, Map.of());
  }

  private SelectionSnapshot resolveSelections(
      QueryContext context,
      List<BomAlternativeGroup> groups,
      Map<String, String> selectionOverrides) {
    QuoteBomAlternativeSelectionScope scope = selectionScope(context);
    Map<String, String> selected = new LinkedHashMap<>();
    List<QuoteEffectiveBomAlternativeResponse> responses = new ArrayList<>();
    List<QuoteEffectiveBomIssueResponse> issues = new ArrayList<>();
    Map<String, String> overrides =
        selectionOverrides == null ? Map.of() : selectionOverrides;
    Set<String> groupKeys =
        groups.stream().map(BomAlternativeGroup::alternativeGroupKey).collect(Collectors.toSet());
    for (String overrideGroupKey : overrides.keySet()) {
      if (!groupKeys.contains(overrideGroupKey)) {
        throw failure(
            "ALT_GROUP_NOT_FOUND",
            "当前月度原始BOM中不存在待预览的标准/替代组=" + display(overrideGroupKey));
      }
    }
    for (BomAlternativeGroup group : groups) {
      QuoteBomAlternativeSelection current =
          selectionRepository.findCurrent(scope, group.alternativeGroupKey());
      String standard = group.standardCandidate().materialCode();
      String overrideMaterial = trimToNull(overrides.get(group.alternativeGroupKey()));
      if (overrideMaterial != null
          && group.candidates().stream()
              .noneMatch(candidate -> sameText(candidate.materialCode(), overrideMaterial))) {
        throw failure(
            "ALT_CANDIDATE_INVALID",
            "料号"
                + display(overrideMaterial)
                + "不是当前标准/替代组的有效候选");
      }
      boolean currentExists = current != null;
      boolean usable =
          currentExists
              && QuoteBomAlternativeSelection.STATUS_ACTIVE.equals(current.getSelectionStatus())
              && group.candidates().stream()
                  .anyMatch(
                      candidate -> sameText(candidate.materialCode(), current.getSelectedMaterialCode()));
      String selectedMaterial =
          overrideMaterial != null
              ? overrideMaterial
              : currentExists ? current.getSelectedMaterialCode() : standard;
      if (overrideMaterial == null && currentExists && !usable) {
        issues.add(
            new QuoteEffectiveBomIssueResponse(
                "ALT_SOURCE_STALE",
                current.getSelectedMaterialCode(),
                current.getParentPath(),
                "当前已保存的标准/替代选择已不属于月度原始BOM候选，请先刷新替代选择"));
      }
      selected.put(group.alternativeGroupKey(), selectedMaterial);
      String childType =
          group.candidates().stream()
              .filter(candidate -> sameText(candidate.materialCode(), selectedMaterial))
              .map(candidate -> candidate.childType().name())
              .findFirst()
              .orElse(null);
      responses.add(
          new QuoteEffectiveBomAlternativeResponse(
              group.alternativeGroupKey(),
              standard,
              selectedMaterial,
              childType,
              overrideMaterial != null
                  ? "UNSAVED_PREVIEW"
                  : currentExists ? current.getSelectionSource() : "AUTO_STANDARD_PREVIEW",
              currentExists ? current.getSelectionVersion() : null,
              overrideMaterial == null && currentExists ? current.getId() : null,
              overrideMaterial == null && currentExists));
    }
    return new SelectionSnapshot(
        Map.copyOf(selected), List.copyOf(responses), List.copyOf(issues));
  }

  private Map<String, EffectiveBomShapeDecision> resolveShapes(
      QueryContext context, List<BomRawHierarchy> selectedRows) {
    Map<String, List<BomRawHierarchy>> rowsByMaterial = new LinkedHashMap<>();
    for (BomRawHierarchy row : selectedRows) {
      String materialCode = trimToNull(row.getMaterialCode());
      if (materialCode != null) {
        rowsByMaterial.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(row);
      }
    }
    Map<String, EffectiveBomShapeDecision> decisions = new LinkedHashMap<>();
    Map<String, String> sourceShapeByMaterial = new LinkedHashMap<>();
    List<MaterialQuoteShapeRequest> requests = new ArrayList<>();
    for (Map.Entry<String, List<BomRawHierarchy>> entry : rowsByMaterial.entrySet()) {
      String materialCode = entry.getKey();
      if (isShapeLessStructureRoot(context, materialCode, entry.getValue())) {
        decisions.put(materialCode, EffectiveBomShapeDecision.structureRoot(materialCode));
        continue;
      }
      Set<String> sourceShapes =
          entry.getValue().stream()
              .map(BomRawHierarchy::getShapeAttr)
              .map(QuoteEffectiveBomApplicationServiceImpl::normalizedText)
              .collect(Collectors.toCollection(LinkedHashSet::new));
      if (sourceShapes.size() > 1) {
        decisions.put(
            materialCode,
            EffectiveBomShapeDecision.blocked(
                materialCode, "同一原始BOM中该料号存在多个形态属性，不能任选"));
        continue;
      }
      String sourceShape = entry.getValue().getFirst().getShapeAttr();
      sourceShapeByMaterial.put(materialCode, sourceShape);
      requests.add(
          new MaterialQuoteShapeRequest(
              context.organization().materialOrganizationCode(),
              materialCode,
              context.costPeriodMonth(),
              sourceShape));
    }

    Map<String, MaterialQuoteShapeResolution> resolutions;
    try {
      resolutions = materialShapeResolver.resolveAll(requests);
    } catch (RuntimeException exception) {
      String reason =
          "形态批量解析失败: "
              + firstText(exception.getMessage(), exception.getClass().getSimpleName());
      sourceShapeByMaterial.keySet().forEach(
          materialCode ->
              decisions.put(
                  materialCode,
                  EffectiveBomShapeDecision.blocked(materialCode, reason)));
      return Map.copyOf(decisions);
    }

    List<MaterialQuoteShapeResolution> supplierInputs =
        resolutions.values().stream()
            .filter(Objects::nonNull)
            .filter(resolution -> resolution.source() == MaterialQuoteShapeSource.SUPPLIER_RATIO)
            .toList();
    Map<String, SupplierRatioResolution> supplierResolutions;
    try {
      supplierResolutions = supplierRatioShapeResolver.resolveAll(supplierInputs);
    } catch (RuntimeException exception) {
      supplierResolutions = Map.of();
      String reason =
          "供货比例形态批量解析失败: "
              + firstText(exception.getMessage(), exception.getClass().getSimpleName());
      supplierInputs.forEach(
          resolution ->
              decisions.put(
                  resolution.materialCode(),
                  EffectiveBomShapeDecision.blocked(resolution.materialCode(), reason)));
    }

    for (String materialCode : sourceShapeByMaterial.keySet()) {
      if (decisions.containsKey(materialCode)) {
        continue;
      }
      MaterialQuoteShapeResolution resolution = resolutions.get(materialCode);
      if (resolution == null) {
        decisions.put(
            materialCode,
            EffectiveBomShapeDecision.blocked(materialCode, "形态解析服务未返回结果"));
      } else if (resolution.source() == MaterialQuoteShapeSource.SUPPLIER_RATIO) {
        SupplierRatioResolution supplier = supplierResolutions.get(materialCode);
        decisions.put(
            materialCode,
            supplier == null
                ? EffectiveBomShapeDecision.blocked(
                    materialCode, "供货比例形态解析服务未返回结果")
                : EffectiveBomShapeDecision.from(
                    supplier, sourceShapeByMaterial.get(materialCode)));
      } else {
        decisions.put(materialCode, EffectiveBomShapeDecision.from(resolution));
      }
    }
    return Map.copyOf(decisions);
  }

  private static boolean isShapeLessStructureRoot(
      QueryContext context,
      String materialCode,
      List<BomRawHierarchy> rows) {
    return context != null
        && sameText(context.topProductCode(), materialCode)
        && rows != null
        && !rows.isEmpty()
        && rows.stream().allMatch(row -> Integer.valueOf(0).equals(row.getLevel()))
        && rows.stream().allMatch(row -> !StringUtils.hasText(row.getShapeAttr()));
  }

  private void validateFrozenNodes(
      QueryContext context,
      QuoteBomMonthlySnapshot snapshot,
      String buildBatchId,
      List<QuoteEffectiveBomNode> nodes) {
    for (QuoteEffectiveBomNode node : nodes) {
      if (!sameText(buildBatchId, node.getBuildBatchId())
          || !sameText(context.topProductCode(), node.getTopProductCode())
          || !sameText(context.costPeriodMonth(), node.getCostPeriodMonth())
          || !sameText(context.organization().priceOrgCode(), node.getPriceOrgCode())) {
        throw failure(
            "EFFECTIVE_BOM_FROZEN_INVALID", "冻结月度卡片与最终BOM节点的产品、月份或组织证据不一致");
      }
    }
  }

  private List<QuoteEffectiveBomAlternativeResponse> frozenSelections(
      List<QuoteEffectiveBomNode> nodes) {
    Set<Long> selectionIds =
        nodes.stream()
            .map(QuoteEffectiveBomNode::getAlternativeSelectionId)
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    Map<Long, QuoteBomAlternativeSelection> selectionEvidence =
        selectionIds.isEmpty()
            ? Map.of()
            : selectionRepository.findByIds(selectionIds).stream()
                .collect(
                    Collectors.toMap(
                        QuoteBomAlternativeSelection::getId,
                        Function.identity(),
                        (first, ignored) -> first,
                        LinkedHashMap::new));
    Map<String, QuoteEffectiveBomAlternativeResponse> selections = new LinkedHashMap<>();
    for (QuoteEffectiveBomNode node : nodes) {
      String groupKey = trimToNull(node.getAlternativeGroupKey());
      if (groupKey == null) {
        continue;
      }
      QuoteBomAlternativeSelection evidence =
          selectionEvidence.get(node.getAlternativeSelectionId());
      if (evidence != null
          && (!sameText(groupKey, evidence.getAlternativeGroupKey())
              || !sameText(node.getMaterialCode(), evidence.getSelectedMaterialCode()))) {
        throw failure(
            "EFFECTIVE_BOM_FROZEN_INVALID", "冻结最终BOM的替代选择证据与节点不一致: " + groupKey);
      }
      QuoteEffectiveBomAlternativeResponse candidate =
          new QuoteEffectiveBomAlternativeResponse(
              groupKey,
              evidence == null
                  ? "STANDARD".equalsIgnoreCase(node.getAlternativeChildType())
                      ? node.getMaterialCode()
                      : null
                  : evidence.getStandardMaterialCode(),
              node.getMaterialCode(),
              node.getAlternativeChildType(),
              evidence == null
                  ? node.getAlternativeSelectionSource()
                  : evidence.getSelectionSource(),
              evidence == null ? null : evidence.getSelectionVersion(),
              node.getAlternativeSelectionId(),
              true);
      QuoteEffectiveBomAlternativeResponse old = selections.putIfAbsent(groupKey, candidate);
      if (old != null && !sameText(old.selectedMaterialCode(), candidate.selectedMaterialCode())) {
        throw failure(
            "EFFECTIVE_BOM_FROZEN_INVALID", "冻结最终BOM同一替代组出现多个选中料号: " + groupKey);
      }
    }
    return List.copyOf(selections.values());
  }

  private QuoteEffectiveBomResponse blockedResponse(
      QueryContext context,
      QuoteBomMonthlySnapshot snapshot,
      List<QuoteEffectiveBomIssueResponse> issues,
      List<String> warnings) {
    return response(
        "BLOCKED",
        context,
        snapshot,
        snapshot == null ? null : trimToNull(snapshot.getBomBatchId()),
        null,
        null,
        List.of(),
        List.of(),
        new QuoteEffectiveBomExclusionSummaryResponse(true, 0, Map.of()),
        issues,
        warnings);
  }

  private QuoteEffectiveBomResponse response(
      String state,
      QueryContext context,
      QuoteBomMonthlySnapshot snapshot,
      String sourceBomBatchId,
      String buildBatchId,
      String variantHash,
      List<QuoteEffectiveBomNodeResponse> nodes,
      List<QuoteEffectiveBomAlternativeResponse> selections,
      QuoteEffectiveBomExclusionSummaryResponse exclusions,
      List<QuoteEffectiveBomIssueResponse> issues,
      List<String> warnings) {
    return new QuoteEffectiveBomResponse(
        state,
        context.oaNo(),
        context.oaFormItemId(),
        context.costPeriodMonth(),
        context.topProductCode(),
        context.customerKey(),
        context.customerKeySource(),
        context.packageMethod(),
        context.organization().priceOrgCode(),
        context.organization().materialOrganizationCode(),
        snapshot == null ? null : snapshot.getId(),
        sourceBomBatchId,
        buildBatchId,
        variantHash,
        snapshot == null ? null : snapshot.getSourceOaFormItemId(),
        nodes,
        selections,
        exclusions,
        issues,
        distinctWarnings(warnings));
  }

  private QuoteEffectiveBomNodeResponse toNode(
      EffectiveBomNodeDraft node, QuoteEffectiveBomAlternativeResponse selection) {
    return new QuoteEffectiveBomNodeResponse(
        node.nodeKey(),
        node.parentNodeKey(),
        node.nodeLevel(),
        node.sortSeq(),
        node.nodePath(),
        node.materialCode(),
        node.materialName(),
        node.materialSpec(),
        node.qtyPerParent(),
        node.qtyPerTop(),
        node.sourceMaterialShape(),
        node.effectiveMaterialShape() == null ? null : node.effectiveMaterialShape().name(),
        node.shapeResolutionSource() == null ? null : node.shapeResolutionSource().name(),
        node.shapePolicyId(),
        node.shapePolicyFingerprint(),
        node.selectedSupplierRatioId(),
        node.selectedSupplierCode(),
        node.selectedSupplierName(),
        node.selectedSupplyRatio(),
        node.alternativeGroupKey(),
        node.alternativeChildType(),
        selection == null ? null : selection.selectionId(),
        selection == null ? null : selection.selectionSource(),
        node.sourceBomType(),
        node.sourceBomBatchId(),
        node.sourceHierarchyId(),
        node.sourceNodePath());
  }

  private QuoteEffectiveBomNodeResponse toNode(QuoteEffectiveBomNode node) {
    return new QuoteEffectiveBomNodeResponse(
        node.getNodeKey(),
        node.getParentNodeKey(),
        node.getNodeLevel(),
        node.getSortSeq(),
        node.getNodePath(),
        node.getMaterialCode(),
        node.getMaterialName(),
        node.getMaterialSpec(),
        node.getQtyPerParent(),
        node.getQtyPerTop(),
        node.getSourceMaterialShape(),
        node.getEffectiveMaterialShape(),
        node.getShapeResolutionSource(),
        node.getShapePolicyId(),
        node.getShapePolicyFingerprint(),
        node.getSelectedSupplierRatioId(),
        node.getSelectedSupplierCode(),
        node.getSelectedSupplierName(),
        node.getSelectedSupplyRatio(),
        node.getAlternativeGroupKey(),
        node.getAlternativeChildType(),
        node.getAlternativeSelectionId(),
        node.getAlternativeSelectionSource(),
        node.getSourceBomType(),
        node.getSourceBomBatchId(),
        node.getSourceHierarchyId(),
        node.getSourceNodePath());
  }

  private QuoteEffectiveBomIssueResponse toIssue(EffectiveBomBlockIssue issue) {
    return new QuoteEffectiveBomIssueResponse(
        issue.issueCode(), issue.materialCode(), issue.sourcePath(), issue.message());
  }

  private QuoteEffectiveBomIssueResponse toIssue(BomAlternativeGroupIssue issue) {
    return new QuoteEffectiveBomIssueResponse(
        issue.code(), issue.candidateMaterialCode(), issue.parentPath(), issue.message());
  }

  private QuoteEffectiveBomExclusionSummaryResponse exclusionSummary(
      List<EffectiveBomExclusion> exclusions) {
    Map<String, Integer> reasons = new LinkedHashMap<>();
    int total = 0;
    for (EffectiveBomExclusion exclusion : exclusions) {
      total += exclusion.excludedNodeCount();
      reasons.merge(exclusion.reasonCode(), exclusion.excludedNodeCount(), Integer::sum);
    }
    return new QuoteEffectiveBomExclusionSummaryResponse(true, total, reasons);
  }

  private List<String> baseWarnings(QueryContext context) {
    List<String> warnings = new ArrayList<>();
    if (StringUtils.hasText(context.customerWarning())) {
      warnings.add(context.customerWarning().trim());
    }
    return warnings;
  }

  private static boolean isFrozen(QuoteBomMonthlySnapshot snapshot) {
    return snapshot != null && FROZEN.equalsIgnoreCase(trimToNull(snapshot.getFreezeStatus()));
  }

  /**
   * 只有第2步确认记录真正引用了构建，月度树才是不可变的最终版本。
   * 历史上“进入第2步”误写成 FROZEN 的记录仍按可重新计算草稿处理。
   */
  private boolean isFinalFrozen(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    if (!isFrozen(snapshot)) {
      return false;
    }
    if (isConfirmed(context)) {
      return true;
    }
    String buildBatchId = trimToNull(snapshot.getEffectiveBuildBatchId());
    return buildBatchId != null
        && confirmationService.hasActiveConfirmationForBuild(buildBatchId);
  }

  private static boolean readyForConfirmation(DraftEvaluation evaluation) {
    return evaluation != null
        && "DRAFT".equals(evaluation.response().state())
        && evaluation.variantInput() != null;
  }

  private static QuoteBomAlternativeSelectionScope selectionScope(
      QueryContext context) {
    return new QuoteBomAlternativeSelectionScope(
        context.oaNo(),
        context.oaFormItemId(),
        context.topProductCode(),
        context.costPeriodMonth(),
        context.organization().priceOrgCode(),
        context.businessUnit());
  }

  private boolean isConfirmed(QueryContext context) {
    return confirmationService.hasActiveConfirmation(
        context.oaNo(),
        context.oaFormItemId(),
        context.topProductCode(),
        context.costPeriodMonth());
  }

  private String frozenState(
      QueryContext context, QuoteBomMonthlySnapshot snapshot) {
    QuoteBomStatus status =
        statusMapper.selectOne(
            Wrappers.<QuoteBomStatus>lambdaQuery()
                .eq(QuoteBomStatus::getOaFormItemId, context.oaFormItemId())
                .last("LIMIT 1"));
    if (status != null
        && QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode().equals(status.getBomStatus())) {
      return "REUSED";
    }
    return Objects.equals(snapshot.getSourceOaFormItemId(), context.oaFormItemId())
        ? "FROZEN"
        : "REUSED";
  }

  private static String latestSourceBuildBatchId(List<BomRawHierarchy> rows) {
    return rows.stream()
        .filter(row -> StringUtils.hasText(row.getBuildBatchId()))
        .max(
            Comparator.comparing(
                    BomRawHierarchy::getBuiltAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(
                    BomRawHierarchy::getBuildBatchId,
                    Comparator.nullsFirst(Comparator.naturalOrder())))
        .map(BomRawHierarchy::getBuildBatchId)
        .orElse(null);
  }

  private static String formalProductCode(QuoteBomPreparationRecord record) {
    if (PRODUCT_TYPE_NON_BARE.equals(record.getProductType())) {
      return trimToNull(record.getQuoteProductCode());
    }
    return firstText(
        firstText(record.getSourceTopProductCode(), record.getReferenceFinishedCode()),
        record.getQuoteProductCode());
  }

  private static List<String> distinctWarnings(List<String> warnings) {
    if (warnings == null || warnings.isEmpty()) {
      return List.of();
    }
    return warnings.stream().filter(StringUtils::hasText).map(String::trim).distinct().toList();
  }

  private static String required(String value, String field) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + "不能为空");
    }
    return value.trim();
  }

  private static String firstText(String first, String second) {
    String value = trimToNull(first);
    return value == null ? trimToNull(second) : value;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private static boolean sameText(String first, String second) {
    return Objects.equals(normalizedText(first), normalizedText(second));
  }

  private static String normalizedText(String value) {
    return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
  }

  private static String display(Object value) {
    return value == null || value.toString().isBlank() ? "（空）" : value.toString().trim();
  }

  private static QuoteEffectiveBomQueryException failure(String code, String message) {
    return new QuoteEffectiveBomQueryException(code, message);
  }

  private static DraftEvaluation blockedEvaluation(
      QuoteEffectiveBomResponse response) {
    return new DraftEvaluation(response, null, Map.of());
  }

  private static QuoteBomMonthlyFreezeKey monthlyKey(QueryContext context) {
    return new QuoteBomMonthlyFreezeKey(
        context.costPeriodMonth(),
        context.topProductCode(),
        context.customerKey(),
        context.packageMethod(),
        context.organization().priceOrgCode());
  }

  private static Map<String, Long> selectionIds(
      QuoteEffectiveBomResponse response) {
    return response == null ? Map.of() : selectionIds(response.alternativeSelections());
  }

  private static Map<String, Long> selectionIds(
      List<QuoteEffectiveBomAlternativeResponse> selections) {
    Map<String, Long> result = new LinkedHashMap<>();
    for (QuoteEffectiveBomAlternativeResponse selection
        : selections == null
            ? List.<QuoteEffectiveBomAlternativeResponse>of()
            : selections) {
      if (StringUtils.hasText(selection.alternativeGroupKey())
          && selection.selectionId() != null) {
        result.put(selection.alternativeGroupKey().trim(), selection.selectionId());
      }
    }
    return Map.copyOf(result);
  }

  private record QueryContext(
      String oaNo,
      Long oaFormItemId,
      String topProductCode,
      String costPeriodMonth,
      String customerKey,
      String customerKeySource,
      String customerWarning,
      String packageMethod,
      QuoteDataOrganization organization,
      String businessUnit) {}

  private record RawSnapshot(
      List<BomRawHierarchy> rows, String sourceBuildBatchId, List<String> warnings) {}

  private record SelectionSnapshot(
      Map<String, String> selectedMaterialByGroup,
      List<QuoteEffectiveBomAlternativeResponse> responses,
      List<QuoteEffectiveBomIssueResponse> issues) {}

  private record DraftEvaluation(
      QuoteEffectiveBomResponse response,
      EffectiveBomVariantInput variantInput,
      Map<String, Long> alternativeSelectionIds) {}
}
