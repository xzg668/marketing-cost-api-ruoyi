package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.CostItemCategory;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

@Service
public class CostRunResultWriterImpl implements CostRunResultWriter {

  private static final Logger log = LoggerFactory.getLogger(CostRunResultWriterImpl.class);

  private static final String COST_CODE_TOTAL = "TOTAL";
  private static final int MAX_REMARK_LENGTH = 4000;
  private static final String TRUNCATED_SUFFIX = "...(truncated)";
  private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");

  private final CostRunResultService costRunResultService;
  private final CostRunResultMapper costRunResultMapper;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunCostItemMapper costRunCostItemMapper;
  private final PricePrepareItemMapper pricePrepareItemMapper;
  private final CostRunTraceSnapshotService traceSnapshotService;

  public CostRunResultWriterImpl(
      CostRunResultService costRunResultService,
      CostRunResultMapper costRunResultMapper,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunCostItemMapper costRunCostItemMapper,
      PricePrepareItemMapper pricePrepareItemMapper,
      CostRunTraceSnapshotService traceSnapshotService) {
    this.costRunResultService = costRunResultService;
    this.costRunResultMapper = costRunResultMapper;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunCostItemMapper = costRunCostItemMapper;
    this.pricePrepareItemMapper = pricePrepareItemMapper;
    this.traceSnapshotService = traceSnapshotService;
  }

  @Override
  @Transactional
  public void writeQuoteResult(CostRunObjectResult result, OaForm form, OaFormItem item) {
    if (result == null || result.getContext() == null || item == null) {
      return;
    }
    String oaNo = result.getContext().getOaNo();
    String productCode = result.getContext().getProductCode();
    if (!StringUtils.hasText(oaNo) || !StringUtils.hasText(productCode)) {
      return;
    }
    if (StringUtils.hasText(result.getContext().getCostRunNo())) {
      writeVersionedResult(result, form, item, oaNo.trim(), productCode.trim());
    } else {
      // 日常 OA 结果表仍沿用老 Service 写法，保证旧查询口径不变。
      costRunResultService.saveOrUpdate(form, item);
      costRunResultService.updateTotalCost(oaNo.trim(), productCode.trim(), totalCost(result));
    }
    overwritePartItems(result, oaNo.trim(), productCode.trim());
    overwriteCostItems(result, oaNo.trim(), productCode.trim());
    if (StringUtils.hasText(result.getContext().getCostRunNo())) {
      scheduleTraceSnapshotRebuild(traceVersion(result));
    }
  }

  private void scheduleTraceSnapshotRebuild(QuoteCostRunVersion version) {
    if (version == null || !StringUtils.hasText(version.getCostRunNo())) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              rebuildTraceSnapshotSafely(version);
            }
          });
      return;
    }
    rebuildTraceSnapshotSafely(version);
  }

  private void rebuildTraceSnapshotSafely(QuoteCostRunVersion version) {
    try {
      traceSnapshotService.rebuildForVersion(version);
    } catch (Exception ex) {
      log.warn(
          "成本核算底稿生成失败，costRunNo={} versionId={}，不影响成本核算结果写入，可重算恢复",
          version.getCostRunNo(),
          version.getId(),
          ex);
    }
  }

  private void writeVersionedResult(
      CostRunObjectResult result, OaForm form, OaFormItem item, String oaNo, String productCode) {
    String costRunNo = result.getContext().getCostRunNo().trim();
    CostRunResult entity =
        costRunResultMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(CostRunResult.class)
                .eq(CostRunResult::getCostRunNo, costRunNo)
                .last("LIMIT 1"));
    if (entity == null) {
      entity = new CostRunResult();
      entity.setOaNo(oaNo);
      entity.setProductCode(productCode);
      entity.setCostRunNo(costRunNo);
    }
    entity.setOaFormItemId(result.getContext().getOaFormItemId());
    entity.setCostRunVersionId(result.getContext().getCostRunVersionId());
    entity.setProductName(trimToNull(item.getProductName()));
    entity.setProductModel(trimToNull(item.getSunlModel()));
    entity.setCustomerName(form == null ? null : trimToNull(form.getCustomer()));
    entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
    entity.setPeriod(firstText(
        result.getContext().getPricingMonth(),
        buildPeriod(form == null ? null : form.getApplyDate())));
    entity.setPricingMonth(trimToNull(result.getContext().getPricingMonth()));
    entity.setPricePrepareNo(trimToNull(result.getContext().getPricePrepareNo()));
    entity.setPriceTypeConfirmNo(trimToNull(result.getContext().getPriceTypeConfirmNo()));
    entity.setResultStatus("TRIAL");
    entity.setCalcStatus("已核算");
    entity.setCalcAt(LocalDateTime.now());
    entity.setTotalCost(totalCost(result));
    if (entity.getId() == null) {
      costRunResultMapper.insert(entity);
    } else {
      costRunResultMapper.updateById(entity);
    }
  }

  private void overwritePartItems(CostRunObjectResult result, String oaNo, String productCode) {
    if (StringUtils.hasText(result.getContext().getCostRunNo())) {
      costRunPartItemMapper.deleteQuoteItemsByCostRunNo(result.getContext().getCostRunNo().trim());
    } else {
      costRunPartItemMapper.deleteQuoteItems(oaNo, productCode);
    }
    List<CostRunPartItemDto> partItems = result.getPartItems();
    if (partItems == null || partItems.isEmpty()) {
      return;
    }
    for (CostRunPartItemDto item : partItems) {
      if (item == null) {
        continue;
      }
      CostRunPartItem entity = new CostRunPartItem();
      entity.setOaNo(oaNo);
      entity.setOaFormItemId(result.getContext().getOaFormItemId());
      entity.setCostRunVersionId(result.getContext().getCostRunVersionId());
      entity.setCostRunNo(trimToNull(result.getContext().getCostRunNo()));
      entity.setBomRowId(item.getBomRowId());
      entity.setPricePrepareItemId(resolvePricePrepareItemId(result, item));
      entity.setProductCode(firstText(item.getProductCode(), productCode));
      entity.setPartCode(trimToNull(item.getPartCode()));
      entity.setPartName(trimToNull(item.getPartName()));
      entity.setPartDrawingNo(trimToNull(item.getPartDrawingNo()));
      entity.setQty(item.getPartQty());
      entity.setMaterial(trimToNull(item.getMaterial()));
      entity.setShapeAttr(trimToNull(item.getShapeAttr()));
      entity.setPriceSource(trimToNull(item.getPriceSource()));
      entity.setUnitPrice(item.getUnitPrice());
      entity.setAmount(item.getAmount());
      entity.setRemark(truncateRemark(item.getRemark()));
      entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
      costRunPartItemMapper.insert(entity);
    }
  }

  private void overwriteCostItems(CostRunObjectResult result, String oaNo, String productCode) {
    if (StringUtils.hasText(result.getContext().getCostRunNo())) {
      costRunCostItemMapper.deleteQuoteItemsByCostRunNo(result.getContext().getCostRunNo().trim());
    } else {
      costRunCostItemMapper.deleteQuoteItems(oaNo, productCode);
    }
    List<CostRunCostItemDto> costItems = result.getCostItems();
    if (costItems == null || costItems.isEmpty()) {
      return;
    }
    int lineNo = 1;
    for (CostRunCostItemDto item : costItems) {
      if (item == null) {
        continue;
      }
      CostRunCostItem entity = new CostRunCostItem();
      entity.setOaNo(oaNo);
      entity.setOaFormItemId(result.getContext().getOaFormItemId());
      entity.setCostRunVersionId(result.getContext().getCostRunVersionId());
      entity.setCostRunNo(trimToNull(result.getContext().getCostRunNo()));
      entity.setProductCode(productCode);
      entity.setLineNo(lineNo++);
      entity.setCostCode(trimToNull(item.getCostCode()));
      entity.setCostName(trimToNull(item.getCostName()));
      entity.setBaseAmount(item.getBaseAmount());
      entity.setRate(item.getRate());
      entity.setAmount(item.getAmount());
      entity.setRemark(truncateRemark(item.getRemark()));
      entity.setCategory(
          StringUtils.hasText(item.getCategory())
              ? item.getCategory().trim()
              : CostItemCategory.EXPENSE);
      entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
      costRunCostItemMapper.insert(entity);
    }
  }

  private BigDecimal totalCost(CostRunObjectResult result) {
    if (result.getResult() != null && result.getResult().getTotalCost() != null) {
      return result.getResult().getTotalCost();
    }
    for (CostRunCostItemDto item : result.getCostItems()) {
      if (item != null && COST_CODE_TOTAL.equals(trim(item.getCostCode()))) {
        return item.getAmount();
      }
    }
    return null;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String truncateRemark(String value) {
    String text = trimToNull(value);
    if (text == null || text.length() <= MAX_REMARK_LENGTH) {
      return text;
    }
    return text.substring(0, MAX_REMARK_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
  }

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return trimToNull(second);
  }

  private Long resolvePricePrepareItemId(CostRunObjectResult result, CostRunPartItemDto item) {
    if (item.getPricePrepareItemId() != null) {
      return item.getPricePrepareItemId();
    }
    if (!StringUtils.hasText(result.getContext().getPricePrepareNo())
        || !StringUtils.hasText(item.getPartCode())) {
      return null;
    }
    PricePrepareItem prepareItem =
        pricePrepareItemMapper.selectOne(
            com.baomidou.mybatisplus.core.toolkit.Wrappers.lambdaQuery(PricePrepareItem.class)
                .eq(PricePrepareItem::getPrepareNo, result.getContext().getPricePrepareNo().trim())
                .eq(item.getBomRowId() != null, PricePrepareItem::getBomRowId, item.getBomRowId())
                .eq(PricePrepareItem::getMaterialCode, item.getPartCode().trim())
                .last("LIMIT 1"));
    return prepareItem == null ? null : prepareItem.getId();
  }

  private QuoteCostRunVersion traceVersion(CostRunObjectResult result) {
    QuoteCostRunVersion version = new QuoteCostRunVersion();
    version.setId(result.getContext().getCostRunVersionId());
    version.setCostRunNo(trimToNull(result.getContext().getCostRunNo()));
    version.setOaNo(trimToNull(result.getContext().getOaNo()));
    version.setOaFormItemId(result.getContext().getOaFormItemId());
    version.setProductCode(trimToNull(result.getContext().getProductCode()));
    version.setPricingMonth(trimToNull(result.getContext().getPricingMonth()));
    version.setPricePrepareNo(trimToNull(result.getContext().getPricePrepareNo()));
    version.setPriceTypeConfirmNo(trimToNull(result.getContext().getPriceTypeConfirmNo()));
    version.setBomConfirmNo(trimToNull(result.getContext().getBomConfirmNo()));
    version.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
    return version;
  }

  private String buildPeriod(LocalDate applyDate) {
    LocalDate date = applyDate == null ? LocalDate.now() : applyDate;
    return date.format(PERIOD_FORMAT);
  }
}
