package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.CostRunContext;
import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.dto.CostRunResultDto;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;
import com.sanhua.marketingcost.entity.MonthlyRepriceCostItem;
import com.sanhua.marketingcost.entity.MonthlyRepricePartItem;
import com.sanhua.marketingcost.entity.MonthlyRepriceResult;
import com.sanhua.marketingcost.mapper.MonthlyRepriceBatchMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceCostItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepricePartItemMapper;
import com.sanhua.marketingcost.mapper.MonthlyRepriceResultMapper;
import com.sanhua.marketingcost.service.MonthlyRepriceResultWriter;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class MonthlyRepriceResultWriterImpl implements MonthlyRepriceResultWriter {

  private static final String COST_ENGINE_VERSION = "LOCAL_COST_RUN_V1";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String COST_CODE_TOTAL = "TOTAL";
  private static final String COST_CODE_MATERIAL = "MATERIAL";
  private static final String COST_CODE_DIRECT_LABOR = "DIRECT_LABOR";
  private static final String COST_CODE_INDIRECT_LABOR = "INDIRECT_LABOR";
  private static final String COST_CODE_MANUFACTURE_COST = "MANUFACTURE_COST";
  private static final String COST_CODE_MGMT = "MGMT_EXP";
  private static final String COST_CODE_SALES = "SALES_EXP";
  private static final String COST_CODE_FINANCE = "FIN_EXP";
  private static final String COST_CODE_AUX_PREFIX = "AUX_";
  private static final String BATCH_STATUS_CONFIRMED = "CONFIRMED";
  private static final String BATCH_STATUS_CANCELLED = "CANCELLED";

  private final MonthlyRepriceBatchMapper batchMapper;
  private final MonthlyRepriceResultMapper resultMapper;
  private final MonthlyRepricePartItemMapper partItemMapper;
  private final MonthlyRepriceCostItemMapper costItemMapper;

  public MonthlyRepriceResultWriterImpl(
      MonthlyRepriceBatchMapper batchMapper,
      MonthlyRepriceResultMapper resultMapper,
      MonthlyRepricePartItemMapper partItemMapper,
      MonthlyRepriceCostItemMapper costItemMapper) {
    this.batchMapper = batchMapper;
    this.resultMapper = resultMapper;
    this.partItemMapper = partItemMapper;
    this.costItemMapper = costItemMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public void write(CostRunObjectResult result) {
    validate(result);
    CostRunContext context = result.getContext();
    assertWritableBatch(context.getRepriceNo());
    // 月度调价按 reprice_no + calc_object_key 整体覆盖，保证同一任务重复执行不会追加重复结果。
    deleteExisting(context);
    resultMapper.insert(buildResult(result));
    insertPartItems(result);
    insertCostItems(result);
  }

  private void validate(CostRunObjectResult result) {
    if (result == null || result.getContext() == null) {
      throw new IllegalArgumentException("月度调价写入结果不能为空");
    }
    CostRunContext context = result.getContext();
    if (!StringUtils.hasText(context.getRepriceNo())
        || !StringUtils.hasText(context.getCalcObjectKey())) {
      throw new IllegalArgumentException("月度调价写入缺少 repriceNo 或 calcObjectKey");
    }
    if (result.getPartItems() == null || result.getPartItems().isEmpty()) {
      throw new IllegalArgumentException("月度调价部品明细不能为空");
    }
    if (result.getCostItems() == null || result.getCostItems().isEmpty()) {
      throw new IllegalArgumentException("月度调价成本项明细不能为空");
    }
  }

  private void assertWritableBatch(String repriceNo) {
    MonthlyRepriceBatch batch = batchMapper.selectByRepriceNoForUpdate(repriceNo);
    if (batch == null) {
      throw new IllegalArgumentException("月度调价批次不存在：" + repriceNo);
    }
    // CONFIRMED/CANCELLED 都是 API 发布或回滚后的只读点，Worker 迟到写入不能再覆盖明细。
    String status = trim(batch.getStatus());
    if (BATCH_STATUS_CONFIRMED.equals(status) || BATCH_STATUS_CANCELLED.equals(status)) {
      throw new IllegalStateException("已结束的月度调价批次结果只读，不能覆盖写入");
    }
  }

  private void deleteExisting(CostRunContext context) {
    costItemMapper.delete(
        Wrappers.lambdaQuery(MonthlyRepriceCostItem.class)
            .eq(MonthlyRepriceCostItem::getRepriceNo, context.getRepriceNo())
            .eq(MonthlyRepriceCostItem::getCalcObjectKey, context.getCalcObjectKey()));
    partItemMapper.delete(
        Wrappers.lambdaQuery(MonthlyRepricePartItem.class)
            .eq(MonthlyRepricePartItem::getRepriceNo, context.getRepriceNo())
            .eq(MonthlyRepricePartItem::getCalcObjectKey, context.getCalcObjectKey()));
    resultMapper.delete(
        Wrappers.lambdaQuery(MonthlyRepriceResult.class)
            .eq(MonthlyRepriceResult::getRepriceNo, context.getRepriceNo())
            .eq(MonthlyRepriceResult::getCalcObjectKey, context.getCalcObjectKey()));
  }

  private MonthlyRepriceResult buildResult(CostRunObjectResult objectResult) {
    CostRunContext context = objectResult.getContext();
    CostRunResultDto source = objectResult.getResult();
    List<CostRunCostItemDto> costItems = objectResult.getCostItems();
    LocalDateTime now = LocalDateTime.now();
    MonthlyRepriceResult result = new MonthlyRepriceResult();
    fillCommon(result, context);
    result.setOaFormItemId(context.getOaFormItemId());
    result.setTotalCost(firstNonNull(
        source == null ? null : source.getTotalCost(), amountOf(costItems, COST_CODE_TOTAL)));
    result.setMaterialCost(amountOf(costItems, COST_CODE_MATERIAL));
    result.setLaborCost(sumAmounts(costItems, COST_CODE_DIRECT_LABOR, COST_CODE_INDIRECT_LABOR));
    result.setAuxiliaryCost(sumPrefix(costItems, COST_CODE_AUX_PREFIX));
    result.setManufacturingCost(amountOf(costItems, COST_CODE_MANUFACTURE_COST));
    result.setManagementCost(amountOf(costItems, COST_CODE_MGMT));
    result.setSalesCost(amountOf(costItems, COST_CODE_SALES));
    result.setFinanceCost(amountOf(costItems, COST_CODE_FINANCE));
    result.setCostEngineVersion(COST_ENGINE_VERSION);
    result.setPriceVersion(context.getPricingMonth());
    result.setSourceCostResultId(objectResult.getSourceCostResultId());
    result.setCalcStatus(STATUS_SUCCESS);
    result.setCalcMessage("OK");
    result.setCreatedAt(now);
    result.setUpdatedAt(now);
    return result;
  }

  private void insertPartItems(CostRunObjectResult objectResult) {
    CostRunContext context = objectResult.getContext();
    LocalDateTime now = LocalDateTime.now();
    int lineNo = 1;
    for (CostRunPartItemDto source : objectResult.getPartItems()) {
      MonthlyRepricePartItem item = new MonthlyRepricePartItem();
      fillCommon(item, context);
      item.setLineNo(lineNo++);
      item.setPartCode(source.getPartCode());
      item.setPartName(source.getPartName());
      item.setPartDrawingNo(source.getPartDrawingNo());
      item.setMaterial(source.getMaterial());
      item.setShapeAttr(source.getShapeAttr());
      item.setQuantity(source.getPartQty());
      item.setUnitPrice(source.getUnitPrice());
      item.setAmount(source.getAmount());
      item.setPriceSource(source.getPriceSource());
      item.setCalcStatus(STATUS_SUCCESS);
      item.setCalcMessage(source.getRemark());
      item.setCreatedAt(now);
      item.setUpdatedAt(now);
      partItemMapper.insert(item);
    }
  }

  private void insertCostItems(CostRunObjectResult objectResult) {
    CostRunContext context = objectResult.getContext();
    LocalDateTime now = LocalDateTime.now();
    int lineNo = 1;
    for (CostRunCostItemDto source : dedupeCostItems(objectResult.getCostItems()).values()) {
      MonthlyRepriceCostItem item = new MonthlyRepriceCostItem();
      fillCommon(item, context);
      item.setLineNo(lineNo++);
      item.setCostItemCode(source.getCostCode());
      item.setCostItemName(source.getCostName());
      item.setBaseAmount(source.getBaseAmount());
      item.setRate(source.getRate());
      item.setAmount(source.getAmount());
      item.setCalcFormula(source.getRemark());
      item.setCalcStatus(STATUS_SUCCESS);
      item.setCalcMessage(source.getRemark());
      item.setCreatedAt(now);
      item.setUpdatedAt(now);
      costItemMapper.insert(item);
    }
  }

  private Map<String, CostRunCostItemDto> dedupeCostItems(List<CostRunCostItemDto> costItems) {
    Map<String, CostRunCostItemDto> result = new LinkedHashMap<>();
    int fallback = 1;
    for (CostRunCostItemDto item : costItems) {
      if (item == null) {
        continue;
      }
      String key = StringUtils.hasText(item.getCostCode())
          ? item.getCostCode().trim()
          : "_LINE_" + fallback++;
      result.put(key, item);
    }
    return result;
  }

  private void fillCommon(MonthlyRepriceResult target, CostRunContext context) {
    target.setRepriceNo(context.getRepriceNo());
    target.setPricingMonth(context.getPricingMonth());
    target.setBusinessUnitType(context.getBusinessUnitType());
    target.setOaNo(context.getOaNo());
    target.setProductCode(context.getProductCode());
    target.setPackageMethod(context.getPackageMethod());
    target.setCustomerName(context.getCustomerName());
    target.setCalcObjectKey(context.getCalcObjectKey());
  }

  private void fillCommon(MonthlyRepricePartItem target, CostRunContext context) {
    target.setRepriceNo(context.getRepriceNo());
    target.setPricingMonth(context.getPricingMonth());
    target.setBusinessUnitType(context.getBusinessUnitType());
    target.setOaNo(context.getOaNo());
    target.setCalcObjectKey(context.getCalcObjectKey());
    target.setProductCode(context.getProductCode());
    target.setPackageMethod(context.getPackageMethod());
    target.setCustomerName(context.getCustomerName());
  }

  private void fillCommon(MonthlyRepriceCostItem target, CostRunContext context) {
    target.setRepriceNo(context.getRepriceNo());
    target.setPricingMonth(context.getPricingMonth());
    target.setBusinessUnitType(context.getBusinessUnitType());
    target.setOaNo(context.getOaNo());
    target.setCalcObjectKey(context.getCalcObjectKey());
    target.setProductCode(context.getProductCode());
    target.setPackageMethod(context.getPackageMethod());
    target.setCustomerName(context.getCustomerName());
  }

  private BigDecimal amountOf(List<CostRunCostItemDto> items, String costCode) {
    for (CostRunCostItemDto item : items) {
      if (item != null && costCode.equals(trim(item.getCostCode()))) {
        return item.getAmount();
      }
    }
    return null;
  }

  private BigDecimal sumAmounts(List<CostRunCostItemDto> items, String... costCodes) {
    BigDecimal result = BigDecimal.ZERO;
    boolean hasValue = false;
    for (String costCode : costCodes) {
      BigDecimal amount = amountOf(items, costCode);
      if (amount != null) {
        result = result.add(amount);
        hasValue = true;
      }
    }
    return hasValue ? result : null;
  }

  private BigDecimal sumPrefix(List<CostRunCostItemDto> items, String prefix) {
    BigDecimal result = BigDecimal.ZERO;
    boolean hasValue = false;
    for (CostRunCostItemDto item : items) {
      if (item != null && trim(item.getCostCode()).startsWith(prefix) && item.getAmount() != null) {
        result = result.add(item.getAmount());
        hasValue = true;
      }
    }
    return hasValue ? result : null;
  }

  private BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
    return first == null ? second : first;
  }

  private String trim(String value) {
    return StringUtils.hasText(value) ? value.trim() : "";
  }
}
