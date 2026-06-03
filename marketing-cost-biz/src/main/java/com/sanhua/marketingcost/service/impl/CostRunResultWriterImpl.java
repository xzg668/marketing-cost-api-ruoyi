package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.OaForm;
import com.sanhua.marketingcost.entity.OaFormItem;
import com.sanhua.marketingcost.enums.CostItemCategory;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.service.CostRunResultService;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class CostRunResultWriterImpl implements CostRunResultWriter {

  private static final String COST_CODE_TOTAL = "TOTAL";

  private final CostRunResultService costRunResultService;
  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunCostItemMapper costRunCostItemMapper;

  public CostRunResultWriterImpl(
      CostRunResultService costRunResultService,
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunCostItemMapper costRunCostItemMapper) {
    this.costRunResultService = costRunResultService;
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunCostItemMapper = costRunCostItemMapper;
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
    // 日常 OA 结果表仍沿用老 Service 写法，保证前端查询和 lp_cost_run_result 口径不变。
    // 先补齐主表元数据和业务单元，再写总成本，避免新建行 business_unit_type 为空导致数据权限不可见。
    costRunResultService.saveOrUpdate(form, item);
    costRunResultService.updateTotalCost(oaNo.trim(), productCode.trim(), totalCost(result));
    overwritePartItems(result, oaNo.trim(), productCode.trim());
    overwriteCostItems(result, oaNo.trim(), productCode.trim());
  }

  private void overwritePartItems(CostRunObjectResult result, String oaNo, String productCode) {
    costRunPartItemMapper.deleteQuoteItems(oaNo, productCode);
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
      entity.setRemark(trimToNull(item.getRemark()));
      entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
      costRunPartItemMapper.insert(entity);
    }
  }

  private void overwriteCostItems(CostRunObjectResult result, String oaNo, String productCode) {
    costRunCostItemMapper.deleteQuoteItems(oaNo, productCode);
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
      entity.setProductCode(productCode);
      entity.setLineNo(lineNo++);
      entity.setCostCode(trimToNull(item.getCostCode()));
      entity.setCostName(trimToNull(item.getCostName()));
      entity.setBaseAmount(item.getBaseAmount());
      entity.setRate(item.getRate());
      entity.setAmount(item.getAmount());
      entity.setRemark(trimToNull(item.getRemark()));
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

  private String firstText(String first, String second) {
    if (StringUtils.hasText(first)) {
      return first.trim();
    }
    return trimToNull(second);
  }
}
