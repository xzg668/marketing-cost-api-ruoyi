package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.ingest.QuoteCostingWorkspaceResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteProductBomPreparationPreview;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchHeaderResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchRollupComponentResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchTabResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkflowStatusResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeRecognitionResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.BomCostingRowSubRef;
import com.sanhua.marketingcost.entity.MaterialMasterRaw;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuoteCostingWorkspace;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.BomCostingRowSubRefMapper;
import com.sanhua.marketingcost.mapper.MaterialMasterRawMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.enums.QuoteBomStatusCode;
import com.sanhua.marketingcost.enums.QuoteCostRunStatus;
import com.sanhua.marketingcost.service.QuoteCostingWorkspaceService;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import com.sanhua.marketingcost.service.QuoteEffectiveBomCostingService;
import com.sanhua.marketingcost.service.QuotePriceTypeRecognitionService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.QuoteProductBomPreparationService;
import com.sanhua.marketingcost.service.effectivebom.QuoteEffectiveBomFeatureSwitch;
import com.sanhua.marketingcost.service.ingest.QuoteBomStatusService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.service.collaboration.CollaborationCostingGate;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostingWorkbenchServiceImpl implements QuoteCostingWorkbenchService {

  private static final String TAB_READY = "READY";
  private static final String TAB_PENDING = "PENDING";
  private static final String TAB_BLOCKED = "BLOCKED";
  private static final String TAB_PARTIAL = "PARTIAL";
  private static final String TAB_DONE = "DONE";
  private static final String TAB_STALE = "STALE";

  private final OaFormMapper oaFormMapper;
  private final OaFormItemMapper oaFormItemMapper;
  private final QuoteBomStatusMapper quoteBomStatusMapper;
  private final BomCostingRowMapper bomCostingRowMapper;
  private final BomCostingRowSubRefMapper bomCostingRowSubRefMapper;
  private final MaterialMasterRawMapper materialMasterRawMapper;
  private final QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper;
  private final QuoteProductBomCostingBuildService costingBuildService;
  private final QuoteProductBomPreparationService bomPreparationService;
  private final QuoteEffectiveBomCostingService effectiveBomCostingService;
  private final QuoteCostingWorkspaceService workspaceService;
  private final QuoteCostRunVersionInvalidationService costRunVersionInvalidationService;
  private final QuoteEffectiveBomFeatureSwitch effectiveBomFeatureSwitch;
  private final QuoteBomStatusService quoteBomStatusService;
  private final CollaborationCostingGate collaborationCostingGate;
  private final QuotePriceTypeRecognitionService priceTypeRecognitionService;

  public QuoteCostingWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      BomCostingRowSubRefMapper bomCostingRowSubRefMapper,
      MaterialMasterRawMapper materialMasterRawMapper,
      QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper,
      QuoteProductBomCostingBuildService costingBuildService,
      QuoteProductBomPreparationService bomPreparationService,
      QuoteEffectiveBomCostingService effectiveBomCostingService,
      QuoteCostingWorkspaceService workspaceService,
      QuoteCostRunVersionInvalidationService costRunVersionInvalidationService,
      QuoteEffectiveBomFeatureSwitch effectiveBomFeatureSwitch,
      QuoteBomStatusService quoteBomStatusService,
      CollaborationCostingGate collaborationCostingGate,
      QuotePriceTypeRecognitionService priceTypeRecognitionService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.bomCostingRowSubRefMapper = bomCostingRowSubRefMapper;
    this.materialMasterRawMapper = materialMasterRawMapper;
    this.workbenchSummaryMapper = workbenchSummaryMapper;
    this.costingBuildService = costingBuildService;
    this.bomPreparationService = bomPreparationService;
    this.effectiveBomCostingService = effectiveBomCostingService;
    this.workspaceService = workspaceService;
    this.costRunVersionInvalidationService = costRunVersionInvalidationService;
    this.effectiveBomFeatureSwitch = effectiveBomFeatureSwitch;
    this.quoteBomStatusService = quoteBomStatusService;
    this.collaborationCostingGate = collaborationCostingGate;
    this.priceTypeRecognitionService = priceTypeRecognitionService;
  }

  @Override
  @Transactional(readOnly = true)
  public QuoteCostingWorkbenchResponse getWorkbench(String oaNo, Long oaFormItemId) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode = trimToNull(item.getMaterialNo());
    if (productCode == null) {
      throw new QuoteIngestException("当前产品行料号为空，无法发起核算");
    }

    QuoteBomStatus status = latestBomStatus(form.getOaNo(), item.getId());
    String periodMonth = resolvePeriodMonth(form, status, item.getId(), productCode);
    List<BomCostingRow> rows = loadSnapshot(form.getOaNo(), item.getId(), productCode, periodMonth);
    String buildBatchId = latestBuildBatchId(rows);
    QuoteCostingWorkspace workspace = workspaceService.find(item.getId(), periodMonth).orElse(null);
    return buildWorkbenchResponse(
        form, item, status, productCode, periodMonth, rows, false, buildBatchId, workspace);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteCostingWorkbenchResponse launchWorkbench(String oaNo, Long oaFormItemId) {
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode = trimToNull(item.getMaterialNo());
    if (productCode == null) {
      throw new QuoteIngestException("当前产品行料号为空，无法发起核算");
    }
    oaFormMapper.selectIdForCostingUpdate(form.getOaNo());
    collaborationCostingGate.requireReadyAndStart(item.getId(), form.getBusinessUnitType());

    String periodMonth = CostPricingPeriodUtils.currentPricingMonth();
    // 用户明确发起或重新发起当前月核算时，旧的未确认试算不能继续显示为可确认。
    // 仅将同产品、同月份的未完成试算标记为 STALE，历史成功版本保持不变。
    costRunVersionInvalidationService.invalidateProduct(
        form.getOaNo(), item.getId(), productCode, periodMonth);
    boolean generated = false;
    String buildBatchId = null;
    if (effectiveBomFeatureSwitch.isEnabled()) {
      QuoteBomStatusItemResponse checked =
          quoteBomStatusService.checkItemForCostRun(form.getOaNo(), item.getId(), periodMonth);
      if (isCostReadyBomStatus(checked == null ? null : checked.getBomStatus())) {
        // 状态检查只证明 U9/补录源中存在 BOM，不会创建当前产品、当前核算月的准备记录。
        // 单品启动必须先补齐这一步，再生成最终有效 BOM；禁止要求用户预先调用旧准备接口。
        QuoteProductBomPreparationPreview preparation =
            bomPreparationService.prepareByOaFormItem(item.getId(), LocalDate.now());
        if (preparation != null && preparation.ready()) {
          QuoteBomCostingBuildResponse build =
              effectiveBomCostingService.prepareCurrent(form.getOaNo(), item.getId());
          generated = true;
          buildBatchId = build == null ? null : build.buildBatchId();
        } else {
          markWaitingForBom(form, item, productCode, periodMonth);
        }
      } else {
        markWaitingForBom(form, item, productCode, periodMonth);
      }
    } else {
      QuoteBomCostingBuildResponse build =
          costingBuildService.buildByOaFormItem(item.getId(), periodMonth, LocalDate.now());
      generated = true;
      buildBatchId = build == null ? null : build.buildBatchId();
    }

    QuoteBomStatus status = latestBomStatus(form.getOaNo(), item.getId());
    List<BomCostingRow> rows = loadSnapshot(form.getOaNo(), item.getId(), productCode, periodMonth);
    QuoteCostingWorkspace workspace = workspaceService.find(item.getId(), periodMonth).orElse(null);
    return buildWorkbenchResponse(
        form, item, status, productCode, periodMonth, rows, generated, buildBatchId, workspace);
  }

  private QuoteCostingWorkbenchResponse buildWorkbenchResponse(
      OaForm form,
      OaFormItem item,
      QuoteBomStatus status,
      String productCode,
      String periodMonth,
      List<BomCostingRow> rows,
      boolean generated,
      String buildBatchId,
      QuoteCostingWorkspace workspace) {
    QuotePriceTypeRecognitionSummaryResponse latestPriceTypeRecognition =
        automaticPriceTypeSummary(
            form,
            item,
            productCode,
            periodMonth,
            rows,
            workspace);
    QuotePricePrepareSummaryResponse latestPricePrepare =
        currentPricePrepare(form, item, productCode, periodMonth, workspace);
    QuoteCostRunSummaryResponse latestCostRun =
        workbenchSummaryMapper.selectLatestCostRun(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    QuoteCostingWorkflowStatusResponse workflowStatus =
        workflowStatus(
            rows,
            workspace,
            latestPriceTypeRecognition,
            latestPricePrepare,
            latestCostRun);

    QuoteCostingWorkbenchResponse response = new QuoteCostingWorkbenchResponse();
    response.setHeader(toHeader(form, periodMonth));
    response.setItem(toItem(item));
    response.setBomStatus(toBomStatus(item, status, periodMonth));
    response.setPeriodMonth(periodMonth);
    response.setWorkflowStatus(workflowStatus);
    response.setSnapshotGenerated(generated);
    response.setEffectiveBomEnabled(effectiveBomFeatureSwitch.isEnabled());
    response.setBuildBatchId(firstText(buildBatchId, latestBuildBatchId(rows)));
    response.setCostingWorkspace(toWorkspaceResponse(workspace));
    response.setLatestPriceTypeRecognition(latestPriceTypeRecognition);
    response.setLatestPricePrepare(latestPricePrepare);
    response.setLatestCostRun(latestCostRun);
    response.setBomRows(toBomRows(rows));
    response.setTabs(tabs(workflowStatus));
    return response;
  }

  private QuotePricePrepareSummaryResponse currentPricePrepare(
      OaForm form,
      OaFormItem item,
      String productCode,
      String periodMonth,
      QuoteCostingWorkspace workspace) {
    String currentPrepareNo =
        trimToNull(workspace == null ? null : workspace.getCurrentPrepareNo());
    if (currentPrepareNo != null) {
      QuotePricePrepareSummaryResponse current =
          workbenchSummaryMapper.selectPricePrepareByNo(currentPrepareNo);
      if (current != null) {
        return current;
      }
    }
    return workbenchSummaryMapper.selectLatestPricePrepare(
        form.getOaNo(), item.getId(), productCode, periodMonth);
  }

  /**
   * 第三步直接读取当前 BOM 对应的价格类型路由，不生成 OA 价格类型确认批次。
   */
  private QuotePriceTypeRecognitionSummaryResponse automaticPriceTypeSummary(
      OaForm form,
      OaFormItem item,
      String productCode,
      String periodMonth,
      List<BomCostingRow> rows,
      QuoteCostingWorkspace workspace) {
    if (!TAB_DONE.equals(quoteBomStatus(rows, workspace))) {
      return null;
    }
    QuotePriceTypeRecognitionResponse recognition =
        priceTypeRecognitionService.getRecognition(form.getOaNo(), item.getId(), periodMonth);
    if (recognition == null || recognition.getSummary() == null) {
      return null;
    }
    var summary = recognition.getSummary();
    QuotePriceTypeRecognitionSummaryResponse response =
        new QuotePriceTypeRecognitionSummaryResponse();
    response.setOaNo(form.getOaNo());
    response.setOaFormItemId(item.getId());
    response.setProductCode(productCode);
    response.setPeriodMonth(periodMonth);
    response.setBomBuildBatchId(recognition.getBomBuildBatchId());
    response.setTotalCount(summary.getReadyForPricePrepareCount());
    response.setConfirmedCount(summary.getConfiguredTypeCount());
    response.setGapCount(summary.getMissingTypeCount());
    response.setReferencePriceCount(summary.getReferencePriceCount());
    boolean ready = !positive(summary.getMissingTypeCount());
    response.setStatus(ready ? "AUTO_READY" : "MISSING_PRICE_TYPE");
    response.setMessage(ready ? "价格类型已自动识别" : "存在缺失价格类型");

    return response;
  }

  private void markWaitingForBom(
      OaForm form, OaFormItem item, String productCode, String periodMonth) {
    QuoteCostingWorkspace workspace =
        workspaceService.lockOrCreate(
            form.getOaNo(),
            item.getId(),
            productCode,
            periodMonth,
            firstText(item.getBusinessUnitType(), form.getBusinessUnitType()));
    int expectedVersion = workspace.getLockVersion() == null ? 0 : workspace.getLockVersion();
    workspace.setWorkspaceStatus("WAIT_BOM");
    workspace.setCurrentStep("QUOTE_BOM");
    workspace.setStaleReasonCode("BOM_MISSING");
    workspace.setLastErrorStep("QUOTE_BOM");
    workspace.setLastErrorCode("BOM_MISSING");
    workspace.setLastErrorMessage("当前产品没有可用于核算的 BOM，请由产品技术补录后重试");
    workspace.setLastCheckedAt(LocalDateTime.now());
    workspaceService.update(workspace, expectedVersion);
  }

  private boolean isCostReadyBomStatus(String status) {
    return QuoteBomStatusCode.SYNCED.getCode().equals(status)
        || QuoteBomStatusCode.REUSED_CURRENT_MONTH.getCode().equals(status)
        || QuoteBomStatusCode.CURRENT_MONTH_QUOTED.getCode().equals(status)
        || QuoteBomStatusCode.U9_BOM_EXISTS.getCode().equals(status)
        || QuoteBomStatusCode.MANUAL_ENTERED.getCode().equals(status);
  }

  private OaForm requireForm(String oaNo) {
    String normalized = trimToNull(oaNo);
    if (normalized == null) {
      throw new QuoteIngestException("报价单号不能为空");
    }
    OaForm form =
        oaFormMapper.selectOne(Wrappers.<OaForm>lambdaQuery().eq(OaForm::getOaNo, normalized));
    if (form == null) {
      throw new QuoteIngestException("报价单不存在: " + normalized);
    }
    return form;
  }

  private OaFormItem requireItem(OaForm form, Long oaFormItemId) {
    if (oaFormItemId == null) {
      throw new QuoteIngestException("报价产品行 ID 不能为空");
    }
    OaFormItem item = oaFormItemMapper.selectById(oaFormItemId);
    if (item == null || !form.getId().equals(item.getOaFormId())) {
      throw new QuoteIngestException("报价产品行不存在或不属于当前报价单: " + oaFormItemId);
    }
    return item;
  }

  private QuoteBomStatus latestBomStatus(String oaNo, Long oaFormItemId) {
    return quoteBomStatusMapper.selectOne(
        Wrappers.<QuoteBomStatus>lambdaQuery()
            .eq(QuoteBomStatus::getOaNo, oaNo)
            .eq(QuoteBomStatus::getOaFormItemId, oaFormItemId)
            .orderByDesc(QuoteBomStatus::getCheckedAt)
            .orderByDesc(QuoteBomStatus::getId)
            .last("LIMIT 1"));
  }

  private List<BomCostingRow> loadSnapshot(
      String oaNo, Long oaFormItemId, String productCode, String periodMonth) {
    return bomCostingRowMapper.selectQuoteCostingSnapshot(
        oaNo, oaFormItemId, productCode, periodMonth);
  }

  private String resolvePeriodMonth(
      OaForm form, QuoteBomStatus status, Long oaFormItemId, String productCode) {
    return CostPricingPeriodUtils.currentPricingMonth();
  }

  private QuoteCostingWorkbenchHeaderResponse toHeader(OaForm form, String periodMonth) {
    QuoteCostingWorkbenchHeaderResponse response = new QuoteCostingWorkbenchHeaderResponse();
    response.setId(form.getId());
    response.setOaNo(form.getOaNo());
    response.setSourceType(form.getSourceType());
    response.setSourceSystem(form.getSourceSystem());
    response.setExternalFormNo(form.getExternalFormNo());
    response.setProcessCode(form.getProcessCode());
    response.setProcessName(form.getProcessName());
    response.setQuoteScenario(form.getQuoteScenario());
    response.setApplyDate(form.getApplyDate());
    response.setCustomer(form.getCustomer());
    response.setApplicantUnit(form.getApplicantUnit());
    response.setApplicantDept(form.getApplicantDept());
    response.setApplicantOffice(form.getApplicantOffice());
    response.setApplicantName(form.getApplicantName());
    response.setCopperPrice(form.getCopperPrice());
    response.setZincPrice(form.getZincPrice());
    response.setAluminumPrice(form.getAluminumPrice());
    response.setSteelPrice(form.getSteelPrice());
    response.setSilverPrice(form.getSilverPrice());
    response.setGoldPrice(form.getGoldPrice());
    response.setSus304Price(form.getSus304Price());
    response.setSus316lPrice(form.getSus316lPrice());
    response.setOtherMaterial(form.getOtherMaterial());
    response.setBaseShipping(form.getBaseShipping());
    response.setCalcStatus(form.getCalcStatus());
    response.setClassificationStatus(form.getClassificationStatus());
    response.setRemark(form.getRemark());
    response.setBusinessUnitType(form.getBusinessUnitType());
    response.setAccountingPeriodMonth(periodMonth);
    response.setCreatedAt(form.getCreatedAt());
    response.setUpdatedAt(form.getUpdatedAt());
    return response;
  }

  private QuoteCostingWorkbenchItemResponse toItem(OaFormItem item) {
    QuoteCostingWorkbenchItemResponse response = new QuoteCostingWorkbenchItemResponse();
    response.setId(item.getId());
    response.setSeq(item.getSeq());
    response.setExternalLineId(item.getExternalLineId());
    response.setMaterialNo(item.getMaterialNo());
    response.setProductName(item.getProductName());
    response.setSunlModel(item.getSunlModel());
    response.setBusinessType(item.getBusinessType());
    response.setPackageType(item.getPackageType());
    response.setPackageMethod(item.getPackageMethod());
    response.setPackageComponentCode(item.getPackageComponentCode());
    response.setAnnualVolume(item.getAnnualVolume());
    response.setTotalWithShip(item.getTotalWithShip());
    response.setTotalNoShip(item.getTotalNoShip());
    response.setTechnicianName(item.getTechnicianName());
    response.setClassificationStatus(item.getClassificationStatus());
    response.setCalcStatus(item.getCalcStatus());
    response.setBusinessUnitType(item.getBusinessUnitType());
    return response;
  }

  private QuoteBomStatusItemResponse toBomStatus(
      OaFormItem item, QuoteBomStatus status, String periodMonth) {
    QuoteBomStatusItemResponse response = new QuoteBomStatusItemResponse();
    response.setSeq(item.getSeq());
    response.setOaFormItemId(item.getId());
    response.setProductCode(item.getMaterialNo());
    response.setProductModel(item.getSunlModel());
    if (status == null) {
      response.setCostPeriodMonth(periodMonth);
      return response;
    }
    response.setId(status.getId());
    response.setProductCode(status.getProductCode());
    response.setProductModel(status.getProductModel());
    response.setBomStatus(status.getBomStatus());
    response.setBomSource(status.getBomSource());
    response.setBomPurpose(status.getBomPurpose());
    response.setBomVersion(status.getBomVersion());
    response.setEffectiveFrom(status.getEffectiveFrom());
    response.setEffectiveTo(status.getEffectiveTo());
    response.setCheckedAt(status.getCheckedAt());
    response.setSyncBatchId(status.getSyncBatchId());
    response.setCostPeriodMonth(periodMonth);
    response.setManualTaskNo(status.getManualTaskNo());
    response.setSupplementTaskId(status.getSupplementTaskId());
    response.setErrorMessage(status.getErrorMessage());
    return response;
  }

  private QuoteCostingWorkspaceResponse toWorkspaceResponse(QuoteCostingWorkspace workspace) {
    if (workspace == null) {
      return null;
    }
    QuoteCostingWorkspaceResponse response = new QuoteCostingWorkspaceResponse();
    response.setPeriodMonth(workspace.getPeriodMonth());
    response.setWorkspaceStatus(workspace.getWorkspaceStatus());
    response.setCurrentStep(workspace.getCurrentStep());
    response.setInputChanged(
        StringUtils.hasText(workspace.getInputFingerprint())
            && StringUtils.hasText(workspace.getLastSuccessInputFingerprint())
            && !workspace.getInputFingerprint().equals(workspace.getLastSuccessInputFingerprint()));
    response.setGapCount(workspace.getGapCount());
    response.setCarriedForwardPriceCount(workspace.getCarriedForwardPriceCount());
    response.setStaleReasonCode(workspace.getStaleReasonCode());
    response.setLastErrorStep(workspace.getLastErrorStep());
    response.setLastErrorCode(workspace.getLastErrorCode());
    response.setLastErrorMessage(workspace.getLastErrorMessage());
    response.setCurrentBomBuildBatchId(workspace.getCurrentBomBuildBatchId());
    response.setCurrentPrepareNo(workspace.getCurrentPrepareNo());
    response.setCurrentCostVersionId(workspace.getCurrentCostVersionId());
    response.setLastTaskId(workspace.getLastTaskId());
    response.setLockVersion(workspace.getLockVersion());
    response.setLastCheckedAt(workspace.getLastCheckedAt());
    return response;
  }

  private List<QuoteCostingWorkbenchBomRowResponse> toBomRows(List<BomCostingRow> rows) {
    List<BomCostingRow> sourceRows = rows == null ? List.of() : rows;
    Map<Long, List<QuoteCostingWorkbenchRollupComponentResponse>> componentsByRowId =
        loadRollupDisplayComponents(sourceRows);
    List<QuoteCostingWorkbenchBomRowResponse> result = new ArrayList<>();
    for (BomCostingRow row : sourceRows) {
      QuoteCostingWorkbenchBomRowResponse response = toBomRow(row);
      response.setRollupComponents(
          row.getId() == null
              ? List.of()
              : componentsByRowId.getOrDefault(row.getId(), List.of()));
      result.add(response);
    }
    return result;
  }

  private Map<Long, List<QuoteCostingWorkbenchRollupComponentResponse>>
      loadRollupDisplayComponents(List<BomCostingRow> rows) {
    List<Long> rowIds =
        rows.stream()
            .filter(row -> "SPECIAL_ROLLUP_PARENT".equals(row.getSettlementRowType()))
            .map(BomCostingRow::getId)
            .filter(Objects::nonNull)
            .toList();
    if (rowIds.isEmpty()) {
      return Map.of();
    }
    List<BomCostingRowSubRef> refs =
        bomCostingRowSubRefMapper.selectSpecialRollupChildren(rowIds);
    if (refs == null || refs.isEmpty()) {
      return Map.of();
    }

    Map<Long, LinkedHashMap<String, RollupDisplayComponentTotal>> totalsByRow =
        new LinkedHashMap<>();
    for (BomCostingRowSubRef ref : refs) {
      String childCode = trimToNull(ref == null ? null : ref.getSubMaterialCode());
      if (childCode == null || ref.getCostingRowId() == null) {
        continue;
      }
      totalsByRow
          .computeIfAbsent(ref.getCostingRowId(), ignored -> new LinkedHashMap<>())
          .computeIfAbsent(childCode, RollupDisplayComponentTotal::new)
          .accept(ref);
    }

    Map<Long, BomCostingRow> rowById = new HashMap<>();
    Map<String, Set<String>> codesByOrganization = new LinkedHashMap<>();
    for (BomCostingRow row : rows) {
      if (row == null || row.getId() == null) {
        continue;
      }
      rowById.put(row.getId(), row);
      Map<String, RollupDisplayComponentTotal> totals = totalsByRow.get(row.getId());
      String organization = trimToNull(row.getMaterialOrganizationCode());
      if (totals == null || totals.isEmpty() || organization == null) {
        continue;
      }
      Set<String> codes =
          codesByOrganization.computeIfAbsent(organization, ignored -> new LinkedHashSet<>());
      codes.addAll(totals.keySet());
      if (StringUtils.hasText(row.getMaterialCode())) {
        codes.add(row.getMaterialCode().trim());
      }
    }
    Map<String, MaterialMasterRaw> archiveByKey = new HashMap<>();
    for (Map.Entry<String, Set<String>> entry : codesByOrganization.entrySet()) {
      List<MaterialMasterRaw> archives =
          materialMasterRawMapper.selectByLatestBatchAndCodes(
              entry.getValue(), null, entry.getKey());
      for (MaterialMasterRaw archive :
          archives == null ? List.<MaterialMasterRaw>of() : archives) {
        String code = trimToNull(archive == null ? null : archive.getMaterialCode());
        if (code != null) {
          archiveByKey.put(materialArchiveKey(entry.getKey(), code), archive);
        }
      }
    }

    Map<Long, List<QuoteCostingWorkbenchRollupComponentResponse>> result =
        new LinkedHashMap<>();
    for (Map.Entry<Long, LinkedHashMap<String, RollupDisplayComponentTotal>> entry :
        totalsByRow.entrySet()) {
      BomCostingRow parent = rowById.get(entry.getKey());
      String organization =
          trimToNull(parent == null ? null : parent.getMaterialOrganizationCode());
      MaterialMasterRaw parentArchive =
          archiveByKey.get(
              materialArchiveKey(
                  organization, parent == null ? null : parent.getMaterialCode()));
      List<QuoteCostingWorkbenchRollupComponentResponse> components = new ArrayList<>();
      for (RollupDisplayComponentTotal total : entry.getValue().values()) {
        MaterialMasterRaw childArchive =
            archiveByKey.get(materialArchiveKey(organization, total.childCode));
        QuoteCostingWorkbenchRollupComponentResponse component =
            new QuoteCostingWorkbenchRollupComponentResponse();
        component.setChildCode(total.childCode);
        component.setChildName(
            firstText(
                total.childName,
                childArchive == null ? null : childArchive.getMaterialName()));
        component.setChildSpec(
            childArchive == null ? null : trimToNull(childArchive.getMaterialSpec()));
        component.setChildModel(
            firstText(
                childArchive == null ? null : childArchive.getMaterialModel(),
                childArchive == null ? null : childArchive.getDrawingNo()));
        component.setChildUnit(
            childArchive == null ? null : trimToNull(childArchive.getUnit()));
        component.setChildMaterialAttribute(
            childArchive == null
                ? null
                : trimToNull(childArchive.getGlobalSeg4Material()));
        component.setChildShapeAttribute(
            childArchive == null ? null : trimToNull(childArchive.getShapeAttr()));
        component.setParentSpec(
            firstText(
                parent == null ? null : parent.getMaterialSpec(),
                parentArchive == null ? null : parentArchive.getMaterialSpec()));
        component.setParentModel(
            firstText(
                parentArchive == null ? null : parentArchive.getMaterialModel(),
                parentArchive == null ? null : parentArchive.getDrawingNo()));
        component.setParentUnit(
            firstText(
                parent == null ? null : parent.getUnit(),
                parentArchive == null ? null : parentArchive.getUnit()));
        component.setParentMaterialAttribute(
            firstText(
                parent == null ? null : parent.getMaterialAttribute(),
                parentArchive == null ? null : parentArchive.getGlobalSeg4Material()));
        component.setParentShapeAttribute(
            firstText(
                parent == null ? null : parent.getShapeAttr(),
                parentArchive == null ? null : parentArchive.getShapeAttr()));
        component.setUsageQty(
            RollupQuantityNormalizer.perParent(
                total.qtyPerTop,
                parent == null ? null : parent.getQtyPerTop(),
                total.firstUsageQty));
        component.setQtyPerTop(total.qtyPerTop);
        components.add(component);
      }
      result.put(entry.getKey(), components);
    }
    return result;
  }

  private static String materialArchiveKey(String organizationCode, String materialCode) {
    return Objects.toString(trimToNull(organizationCode), "")
        + "\u0000"
        + Objects.toString(trimToNull(materialCode), "");
  }

  private static BigDecimal addNullable(BigDecimal left, BigDecimal right) {
    if (left == null) {
      return right;
    }
    return right == null ? left : left.add(right);
  }

  private QuoteCostingWorkbenchBomRowResponse toBomRow(BomCostingRow row) {
    QuoteCostingWorkbenchBomRowResponse response = new QuoteCostingWorkbenchBomRowResponse();
    response.setId(row.getId());
    response.setOaNo(row.getOaNo());
    response.setOaFormItemId(row.getOaFormItemId());
    response.setTopProductCode(row.getTopProductCode());
    response.setPriceOrgCode(row.getPriceOrgCode());
    response.setMaterialOrganizationCode(row.getMaterialOrganizationCode());
    response.setParentCode(row.getParentCode());
    response.setChildCode(row.getMaterialCode());
    response.setChildName(row.getMaterialName());
    response.setChildSpec(row.getMaterialSpec());
    response.setChildModel(row.getMaterialSpec());
    response.setUsageQty(row.getQtyPerParent());
    response.setQtyPerTop(row.getQtyPerTop());
    response.setUnit(row.getUnit());
    response.setMaterialAttribute(row.getMaterialAttribute());
    response.setShapeAttribute(row.getShapeAttr());
    response.setLevel(row.getLevel());
    response.setPath(row.getPath());
    response.setSettlementRowType(row.getSettlementRowType());
    response.setSubtreeCostRequired(row.getSubtreeCostRequired());
    return response;
  }

  private static final class RollupDisplayComponentTotal {
    private final String childCode;
    private String childName;
    private BigDecimal firstUsageQty;
    private BigDecimal qtyPerTop;

    private RollupDisplayComponentTotal(String childCode) {
      this.childCode = childCode;
    }

    private void accept(BomCostingRowSubRef ref) {
      childName = firstText(childName, ref.getSubMaterialName());
      if (firstUsageQty == null) {
        firstUsageQty = ref.getSubQtyPerParent();
      }
      qtyPerTop = addNullable(qtyPerTop, ref.getSubQtyPerTop());
    }
  }

  private QuoteCostingWorkflowStatusResponse workflowStatus(
      List<BomCostingRow> rows,
      QuoteCostingWorkspace workspace,
      QuotePriceTypeRecognitionSummaryResponse priceTypeRecognition,
      QuotePricePrepareSummaryResponse pricePrepare,
      QuoteCostRunSummaryResponse costRun) {
    String quoteBomStatus = quoteBomStatus(rows, workspace);
    String priceTypeStatus = priceTypeStatus(quoteBomStatus, priceTypeRecognition);
    String pricePrepareStatus =
        pricePrepareStatus(priceTypeStatus, workspace, pricePrepare);
    String costRunStatus = costRunStatus(pricePrepareStatus, pricePrepare, costRun);

    QuoteCostingWorkflowStatusResponse response = new QuoteCostingWorkflowStatusResponse();
    response.setProductDetailStatus(TAB_DONE);
    response.setQuoteBomStatus(quoteBomStatus);
    response.setPriceTypeConfirmationStatus(priceTypeStatus);
    response.setPricePrepareStatus(pricePrepareStatus);
    response.setCostRunStatus(costRunStatus);
    response.setOverallStatus(costRunStatus);
    response.setCurrentBlockedStep(currentBlockedStep(response));
    return response;
  }

  private String quoteBomStatus(
      List<BomCostingRow> rows, QuoteCostingWorkspace workspace) {
    if (workspace != null && "STALE".equalsIgnoreCase(trimToNull(workspace.getWorkspaceStatus()))) {
      return TAB_STALE;
    }
    if (workspace != null
        && "WAIT_BOM".equalsIgnoreCase(trimToNull(workspace.getWorkspaceStatus()))) {
      return TAB_BLOCKED;
    }
    if (rows == null || rows.isEmpty()) {
      return TAB_BLOCKED;
    }
    return TAB_DONE;
  }

  private String priceTypeStatus(
      String quoteBomStatus, QuotePriceTypeRecognitionSummaryResponse confirmation) {
    if (!TAB_DONE.equals(quoteBomStatus)) {
      return TAB_BLOCKED;
    }
    if (confirmation == null) {
      return TAB_PENDING;
    }
    if ("STALE".equalsIgnoreCase(trimToNull(confirmation.getStatus()))) {
      return TAB_STALE;
    }
    if (positive(confirmation.getGapCount())
        || "MISSING_TYPE".equalsIgnoreCase(trimToNull(confirmation.getStatus()))
        || "MISSING_PRICE_TYPE".equalsIgnoreCase(trimToNull(confirmation.getStatus()))) {
      return TAB_PARTIAL;
    }
    if ("AUTO_READY".equalsIgnoreCase(trimToNull(confirmation.getStatus()))
        || "CONFIRMED".equalsIgnoreCase(trimToNull(confirmation.getStatus()))) {
      return TAB_DONE;
    }
    return TAB_PENDING;
  }

  private String pricePrepareStatus(
      String priceTypeStatus,
      QuoteCostingWorkspace workspace,
      QuotePricePrepareSummaryResponse pricePrepare) {
    if (!TAB_DONE.equals(priceTypeStatus)) {
      return TAB_BLOCKED;
    }
    String workspaceStatus = trimToNull(workspace == null ? null : workspace.getWorkspaceStatus());
    if ("PRICE_BLOCKED".equalsIgnoreCase(workspaceStatus)
        || "PRICE_ERROR".equalsIgnoreCase(workspaceStatus)
        || positive(workspace == null ? null : workspace.getGapCount())) {
      return TAB_PARTIAL;
    }
    if (pricePrepare == null) {
      return TAB_PENDING;
    }
    if ("STALE".equalsIgnoreCase(trimToNull(pricePrepare.getStatus()))) {
      return TAB_STALE;
    }
    if (positive(pricePrepare.getGapCount())
        || "PARTIAL".equalsIgnoreCase(trimToNull(pricePrepare.getStatus()))) {
      return TAB_PARTIAL;
    }
    if ("SUCCESS".equalsIgnoreCase(trimToNull(pricePrepare.getStatus()))
        || "DONE".equalsIgnoreCase(trimToNull(pricePrepare.getStatus()))) {
      return TAB_DONE;
    }
    return TAB_PENDING;
  }

  private String costRunStatus(
      String pricePrepareStatus,
      QuotePricePrepareSummaryResponse pricePrepare,
      QuoteCostRunSummaryResponse costRun) {
    if (!TAB_DONE.equals(pricePrepareStatus)) {
      return TAB_BLOCKED;
    }
    if (costRun == null) {
      return TAB_PENDING;
    }
    if (!Objects.equals(
        trimToNull(pricePrepare == null ? null : pricePrepare.getPrepareNo()),
        trimToNull(firstText(costRun.getOaPricePrepareNo(), costRun.getPricePrepareNo())))) {
      return TAB_PENDING;
    }
    if ("STALE".equalsIgnoreCase(trimToNull(costRun.getStatus()))) {
      return TAB_STALE;
    }
    if (QuoteCostRunStatus.isCurrentSuccess(costRun.getStatus())) {
      return TAB_DONE;
    }
    if (QuoteCostRunStatus.isInProgress(costRun.getStatus())) {
      return TAB_PARTIAL;
    }
    return TAB_PENDING;
  }

  private String currentBlockedStep(QuoteCostingWorkflowStatusResponse status) {
    if (!TAB_DONE.equals(status.getQuoteBomStatus())) {
      return "QUOTE_BOM";
    }
    if (!TAB_DONE.equals(status.getPriceTypeConfirmationStatus())) {
      return "PRICE_TYPE_CONFIRMATION";
    }
    if (!TAB_DONE.equals(status.getPricePrepareStatus())) {
      return "PRICE_PREPARE";
    }
    if (!TAB_DONE.equals(status.getCostRunStatus())) {
      return "COST_RUN";
    }
    return null;
  }

  private boolean positive(Integer value) {
    return value != null && value > 0;
  }

  private List<QuoteCostingWorkbenchTabResponse> tabs(QuoteCostingWorkflowStatusResponse status) {
    List<QuoteCostingWorkbenchTabResponse> tabs = new ArrayList<>();
    tabs.add(tab("PRODUCT_DETAIL", "产品详情", status.getProductDetailStatus(), null));
    tabs.add(tab("QUOTE_BOM", "报价物料明细", status.getQuoteBomStatus(), null));
    tabs.add(
        tab(
            "PRICE_TYPE_CONFIRMATION",
            "价格类型识别",
            status.getPriceTypeConfirmationStatus(),
            blockedReason(status.getPriceTypeConfirmationStatus(), "请先生成报价物料")));
    tabs.add(
        tab(
            "PRICE_PREPARE",
            "价格准备",
            status.getPricePrepareStatus(),
            blockedReason(status.getPricePrepareStatus(), "请先补齐价格类型")));
    tabs.add(
        tab(
            "COST_RUN",
            "成本核算",
            status.getCostRunStatus(),
            blockedReason(status.getCostRunStatus(), "请先完成价格准备")));
    return tabs;
  }

  private String blockedReason(String status, String reason) {
    return TAB_BLOCKED.equals(status) ? reason : null;
  }

  private QuoteCostingWorkbenchTabResponse tab(
      String code, String name, String status, String blockedReason) {
    QuoteCostingWorkbenchTabResponse response = new QuoteCostingWorkbenchTabResponse();
    response.setCode(code);
    response.setName(name);
    response.setStatus(status);
    response.setBlockedReason(blockedReason);
    return response;
  }

  private String latestBuildBatchId(List<BomCostingRow> rows) {
    if (rows == null || rows.isEmpty()) {
      return null;
    }
    return rows.get(0).getBuildBatchId();
  }

  private static String firstText(String first, String second) {
    String normalized = trimToNull(first);
    return normalized == null ? trimToNull(second) : normalized;
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

}
