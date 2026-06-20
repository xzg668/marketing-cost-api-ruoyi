package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.ingest.QuoteBomStatusItemResponse;
import com.sanhua.marketingcost.dto.quotebom.QuoteBomCostingBuildResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingBomRowUpdateRequest;
import com.sanhua.marketingcost.dto.quotecosting.QuoteBomConfirmationSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostRunSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchBomRowResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchHeaderResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchItemResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkbenchTabResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuoteCostingWorkflowStatusResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePricePrepareSummaryResponse;
import com.sanhua.marketingcost.dto.quotecosting.QuotePriceTypeConfirmationSummaryResponse;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.QuoteBomConfirmation;
import com.sanhua.marketingcost.entity.QuoteBomStatus;
import com.sanhua.marketingcost.entity.QuotePriceTypeConfirmBatch;
import com.sanhua.marketingcost.mapper.BomCostingRowMapper;
import com.sanhua.marketingcost.mapper.OaFormItemMapper;
import com.sanhua.marketingcost.mapper.OaFormMapper;
import com.sanhua.marketingcost.mapper.QuoteBomConfirmationMapper;
import com.sanhua.marketingcost.mapper.QuoteBomStatusMapper;
import com.sanhua.marketingcost.mapper.QuoteCostingWorkbenchSummaryMapper;
import com.sanhua.marketingcost.mapper.QuotePriceTypeConfirmBatchMapper;
import com.sanhua.marketingcost.service.QuoteCostingWorkbenchService;
import com.sanhua.marketingcost.service.QuoteProductBomCostingBuildService;
import com.sanhua.marketingcost.service.ingest.QuoteIngestException;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
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
  private final QuoteBomConfirmationMapper quoteBomConfirmationMapper;
  private final QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper;
  private final QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper;
  private final QuoteProductBomCostingBuildService costingBuildService;

  public QuoteCostingWorkbenchServiceImpl(
      OaFormMapper oaFormMapper,
      OaFormItemMapper oaFormItemMapper,
      QuoteBomStatusMapper quoteBomStatusMapper,
      BomCostingRowMapper bomCostingRowMapper,
      QuoteBomConfirmationMapper quoteBomConfirmationMapper,
      QuoteCostingWorkbenchSummaryMapper workbenchSummaryMapper,
      QuotePriceTypeConfirmBatchMapper priceTypeConfirmBatchMapper,
      QuoteProductBomCostingBuildService costingBuildService) {
    this.oaFormMapper = oaFormMapper;
    this.oaFormItemMapper = oaFormItemMapper;
    this.quoteBomStatusMapper = quoteBomStatusMapper;
    this.bomCostingRowMapper = bomCostingRowMapper;
    this.quoteBomConfirmationMapper = quoteBomConfirmationMapper;
    this.workbenchSummaryMapper = workbenchSummaryMapper;
    this.priceTypeConfirmBatchMapper = priceTypeConfirmBatchMapper;
    this.costingBuildService = costingBuildService;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
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
    boolean generated = false;
    String buildBatchId = latestBuildBatchId(rows);

    if (rows.isEmpty()) {
      LocalDate costRunLaunchDate = LocalDate.now();
      QuoteBomCostingBuildResponse build =
          costingBuildService.buildByOaFormItem(item.getId(), periodMonth, costRunLaunchDate);
      generated = true;
      periodMonth = firstText(build.periodMonth(), periodMonth);
      buildBatchId = build.buildBatchId();
      rows = loadSnapshot(form.getOaNo(), item.getId(), productCode, periodMonth);
    }

    QuoteBomConfirmationSummaryResponse latestBomConfirmation =
        workbenchSummaryMapper.selectLatestBomConfirmation(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    QuotePriceTypeConfirmationSummaryResponse latestPriceTypeConfirmation =
        workbenchSummaryMapper.selectLatestPriceTypeConfirmation(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    QuotePricePrepareSummaryResponse latestPricePrepare =
        workbenchSummaryMapper.selectLatestPricePrepare(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    QuoteCostRunSummaryResponse latestCostRun =
        workbenchSummaryMapper.selectLatestCostRun(
            form.getOaNo(), item.getId(), productCode, periodMonth);
    QuoteCostingWorkflowStatusResponse workflowStatus =
        workflowStatus(
            rows,
            latestBomConfirmation,
            latestPriceTypeConfirmation,
            latestPricePrepare,
            latestCostRun);

    QuoteCostingWorkbenchResponse response = new QuoteCostingWorkbenchResponse();
    response.setHeader(toHeader(form, periodMonth));
    response.setItem(toItem(item));
    response.setBomStatus(toBomStatus(item, status, periodMonth));
    response.setPeriodMonth(periodMonth);
    response.setWorkflowStatus(workflowStatus);
    response.setSnapshotGenerated(generated);
    response.setBuildBatchId(firstText(buildBatchId, latestBuildBatchId(rows)));
    response.setLatestBomConfirmation(latestBomConfirmation);
    response.setLatestPriceTypeConfirmation(latestPriceTypeConfirmation);
    response.setLatestPricePrepare(latestPricePrepare);
    response.setLatestCostRun(latestCostRun);
    response.setBomRows(toBomRows(rows));
    response.setTabs(tabs(workflowStatus));
    return response;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public QuoteCostingWorkbenchBomRowResponse updateBomRow(
      String oaNo, Long oaFormItemId, Long rowId, QuoteCostingBomRowUpdateRequest request) {
    if (rowId == null) {
      throw new QuoteIngestException("BOM 行 ID 不能为空");
    }
    if (request == null) {
      throw new QuoteIngestException("BOM 行更新内容不能为空");
    }
    OaForm form = requireForm(oaNo);
    OaFormItem item = requireItem(form, oaFormItemId);
    String productCode = trimToNull(item.getMaterialNo());
    if (productCode == null) {
      throw new QuoteIngestException("当前产品行料号为空，无法编辑 BOM 行");
    }
    QuoteBomStatus status = latestBomStatus(form.getOaNo(), item.getId());
    String periodMonth = resolvePeriodMonth(form, status, item.getId(), productCode);
    BomCostingRow existing = bomCostingRowMapper.selectById(rowId);
    requireEditableRow(existing, form.getOaNo(), item.getId(), productCode, periodMonth);
    requireNoActiveBomConfirmation(form.getOaNo(), item.getId(), productCode, periodMonth);

    LocalDateTime now = LocalDateTime.now();
    String oldMaterialCode = trimToNull(existing.getMaterialCode());
    String newMaterialCode = trimToNull(request.getChildCode());
    BomCostingRow patch = new BomCostingRow();
    patch.setId(rowId);
    patch.setMaterialCode(newMaterialCode);
    patch.setMaterialName(trimToNull(request.getChildName()));
    patch.setMaterialSpec(trimToNull(request.getChildModel()));
    patch.setQtyPerParent(request.getUsageQty());
    patch.setUnit(trimToNull(request.getUnit()));
    patch.setMaterialAttribute(trimToNull(request.getMaterialAttribute()));
    patch.setShapeAttr(trimToNull(request.getShapeAttribute()));
    patch.setManualModified(1);
    patch.setModifiedBy(currentUsername("system"));
    patch.setModifiedAt(now);
    if (bomCostingRowMapper.updateById(patch) <= 0) {
      throw new QuoteIngestException("BOM 行保存失败: " + rowId);
    }
    if (!Objects.equals(oldMaterialCode, newMaterialCode)) {
      markPriceTypeConfirmationStale(form.getOaNo(), item.getId(), productCode, periodMonth, now);
    }

    existing.setMaterialCode(patch.getMaterialCode());
    existing.setMaterialName(patch.getMaterialName());
    existing.setMaterialSpec(patch.getMaterialSpec());
    existing.setQtyPerParent(patch.getQtyPerParent());
    existing.setUnit(patch.getUnit());
    existing.setMaterialAttribute(patch.getMaterialAttribute());
    existing.setShapeAttr(patch.getShapeAttr());
    existing.setManualModified(patch.getManualModified());
    existing.setModifiedBy(patch.getModifiedBy());
    existing.setModifiedAt(patch.getModifiedAt());
    return toBomRow(existing);
  }

  private void markPriceTypeConfirmationStale(
      String oaNo, Long oaFormItemId, String productCode, String periodMonth, LocalDateTime now) {
    priceTypeConfirmBatchMapper.update(
        null,
        Wrappers.<QuotePriceTypeConfirmBatch>lambdaUpdate()
            .set(QuotePriceTypeConfirmBatch::getStatus, QuotePriceTypeConfirmBatch.STATUS_STALE)
            .set(QuotePriceTypeConfirmBatch::getUpdatedAt, now)
            .eq(QuotePriceTypeConfirmBatch::getOaNo, oaNo)
            .eq(QuotePriceTypeConfirmBatch::getOaFormItemId, oaFormItemId)
            .eq(QuotePriceTypeConfirmBatch::getProductCode, productCode)
            .eq(QuotePriceTypeConfirmBatch::getPeriodMonth, periodMonth)
            .eq(QuotePriceTypeConfirmBatch::getStatus, QuotePriceTypeConfirmBatch.STATUS_CONFIRMED));
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

  private List<QuoteCostingWorkbenchBomRowResponse> toBomRows(List<BomCostingRow> rows) {
    List<QuoteCostingWorkbenchBomRowResponse> result = new ArrayList<>();
    for (BomCostingRow row : rows == null ? List.<BomCostingRow>of() : rows) {
      result.add(toBomRow(row));
    }
    return result;
  }

  private QuoteCostingWorkbenchBomRowResponse toBomRow(BomCostingRow row) {
    QuoteCostingWorkbenchBomRowResponse response = new QuoteCostingWorkbenchBomRowResponse();
    response.setId(row.getId());
    response.setOaNo(row.getOaNo());
    response.setOaFormItemId(row.getOaFormItemId());
    response.setTopProductCode(row.getTopProductCode());
    response.setParentCode(row.getParentCode());
    response.setChildCode(row.getMaterialCode());
    response.setChildName(row.getMaterialName());
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
    response.setManualModified(row.getManualModified());
    response.setModifiedBy(row.getModifiedBy());
    response.setModifiedAt(row.getModifiedAt());
    return response;
  }

  private void requireEditableRow(
      BomCostingRow row, String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    if (row == null) {
      throw new QuoteIngestException("BOM 行不存在");
    }
    if (!Objects.equals(row.getOaNo(), oaNo)) {
      throw new QuoteIngestException("BOM 行不属于当前报价单");
    }
    if (!Objects.equals(row.getOaFormItemId(), oaFormItemId)) {
      throw new QuoteIngestException("BOM 行不属于当前产品行");
    }
    if (!Objects.equals(row.getTopProductCode(), topProductCode)) {
      throw new QuoteIngestException("BOM 行不属于当前报价料号");
    }
    if (!Objects.equals(row.getPeriodMonth(), periodMonth)) {
      throw new QuoteIngestException("BOM 行不属于当前核算月份");
    }
  }

  private void requireNoActiveBomConfirmation(
      String oaNo, Long oaFormItemId, String topProductCode, String periodMonth) {
    QuoteBomConfirmation confirmation =
        quoteBomConfirmationMapper.selectOne(
            Wrappers.<QuoteBomConfirmation>lambdaQuery()
                .eq(QuoteBomConfirmation::getOaNo, oaNo)
                .eq(QuoteBomConfirmation::getOaFormItemId, oaFormItemId)
                .eq(QuoteBomConfirmation::getTopProductCode, topProductCode)
                .eq(QuoteBomConfirmation::getPeriodMonth, periodMonth)
                .eq(QuoteBomConfirmation::getConfirmStatus, QuoteBomConfirmation.STATUS_CONFIRMED)
                .orderByDesc(QuoteBomConfirmation::getConfirmedAt)
                .orderByDesc(QuoteBomConfirmation::getId)
                .last("LIMIT 1"));
    if (confirmation != null) {
      throw new QuoteIngestException("当前产品行 BOM 已确认，请先撤销确认后再编辑");
    }
  }

  private QuoteCostingWorkflowStatusResponse workflowStatus(
      List<BomCostingRow> rows,
      QuoteBomConfirmationSummaryResponse bomConfirmation,
      QuotePriceTypeConfirmationSummaryResponse priceTypeConfirmation,
      QuotePricePrepareSummaryResponse pricePrepare,
      QuoteCostRunSummaryResponse costRun) {
    String quoteBomStatus = quoteBomStatus(rows, bomConfirmation);
    String priceTypeStatus = priceTypeStatus(quoteBomStatus, priceTypeConfirmation);
    String pricePrepareStatus = pricePrepareStatus(priceTypeStatus, priceTypeConfirmation, pricePrepare);
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
      List<BomCostingRow> rows, QuoteBomConfirmationSummaryResponse bomConfirmation) {
    if (rows == null || rows.isEmpty()) {
      return TAB_BLOCKED;
    }
    if (bomConfirmation == null) {
      return TAB_PENDING;
    }
    if ("CONFIRMED".equalsIgnoreCase(trimToNull(bomConfirmation.getConfirmStatus()))) {
      return TAB_DONE;
    }
    if ("STALE".equalsIgnoreCase(trimToNull(bomConfirmation.getConfirmStatus()))) {
      return TAB_STALE;
    }
    return TAB_PENDING;
  }

  private String priceTypeStatus(
      String quoteBomStatus, QuotePriceTypeConfirmationSummaryResponse confirmation) {
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
        || "MISSING_TYPE".equalsIgnoreCase(trimToNull(confirmation.getStatus()))) {
      return TAB_PARTIAL;
    }
    if ("CONFIRMED".equalsIgnoreCase(trimToNull(confirmation.getStatus()))) {
      return TAB_DONE;
    }
    return TAB_PENDING;
  }

  private String pricePrepareStatus(
      String priceTypeStatus,
      QuotePriceTypeConfirmationSummaryResponse confirmation,
      QuotePricePrepareSummaryResponse pricePrepare) {
    if (!TAB_DONE.equals(priceTypeStatus)) {
      return TAB_BLOCKED;
    }
    if (pricePrepare == null) {
      return TAB_PENDING;
    }
    if (!Objects.equals(
        trimToNull(confirmation == null ? null : confirmation.getConfirmNo()),
        trimToNull(pricePrepare.getPriceTypeConfirmNo()))) {
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
        trimToNull(costRun.getPricePrepareNo()))) {
      return TAB_PENDING;
    }
    if ("STALE".equalsIgnoreCase(trimToNull(costRun.getStatus()))) {
      return TAB_STALE;
    }
    if ("CONFIRMED".equalsIgnoreCase(trimToNull(costRun.getStatus()))) {
      return TAB_DONE;
    }
    if ("TRIAL".equalsIgnoreCase(trimToNull(costRun.getStatus()))) {
      return TAB_PARTIAL;
    }
    return TAB_PENDING;
  }

  private String currentBlockedStep(QuoteCostingWorkflowStatusResponse status) {
    if (TAB_BLOCKED.equals(status.getQuoteBomStatus())) {
      return "QUOTE_BOM";
    }
    if (TAB_BLOCKED.equals(status.getPriceTypeConfirmationStatus())) {
      return "PRICE_TYPE_CONFIRMATION";
    }
    if (TAB_BLOCKED.equals(status.getPricePrepareStatus())) {
      return "PRICE_PREPARE";
    }
    if (TAB_BLOCKED.equals(status.getCostRunStatus())) {
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
    tabs.add(tab("QUOTE_BOM", "报价物料确认", status.getQuoteBomStatus(), null));
    tabs.add(
        tab(
            "PRICE_TYPE_CONFIRMATION",
            "价格类型确认",
            status.getPriceTypeConfirmationStatus(),
            blockedReason(status.getPriceTypeConfirmationStatus(), "请先确认报价物料")));
    tabs.add(
        tab(
            "PRICE_PREPARE",
            "价格准备",
            status.getPricePrepareStatus(),
            blockedReason(status.getPricePrepareStatus(), "请先确认价格类型")));
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

  private String currentUsername(String fallback) {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication.getPrincipal() == null) {
      return fallback;
    }
    Object principal = authentication.getPrincipal();
    if (principal instanceof UserDetails userDetails) {
      return StringUtils.hasText(userDetails.getUsername()) ? userDetails.getUsername() : fallback;
    }
    String value = principal.toString();
    return StringUtils.hasText(value) ? value : fallback;
  }
}
