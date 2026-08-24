package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.CostRunCostItemDto;
import com.sanhua.marketingcost.dto.CostRunObjectResult;
import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import com.sanhua.marketingcost.entity.CostRunCostItem;
import com.sanhua.marketingcost.entity.CostRunPartItem;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.enums.CostItemCategory;
import com.sanhua.marketingcost.mapper.CostRunCostItemMapper;
import com.sanhua.marketingcost.mapper.CostRunPartItemMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.CostRunResultWriter;
import com.sanhua.marketingcost.service.CostRunTraceSnapshotService;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 成本版本明细写入器；结果头统一由 lp_quote_cost_run_version 保存。 */
@Service
public class CostRunResultWriterImpl implements CostRunResultWriter {

  private static final int MAX_PART_REMARK_LENGTH = 200;
  private static final int MAX_COST_REMARK_LENGTH = 4000;
  private static final String TRUNCATED_SUFFIX = "...(truncated)";

  private final CostRunPartItemMapper costRunPartItemMapper;
  private final CostRunCostItemMapper costRunCostItemMapper;
  private final PricePrepareItemMapper pricePrepareItemMapper;
  private final CostRunTraceSnapshotService traceSnapshotService;

  public CostRunResultWriterImpl(
      CostRunPartItemMapper costRunPartItemMapper,
      CostRunCostItemMapper costRunCostItemMapper,
      PricePrepareItemMapper pricePrepareItemMapper,
      CostRunTraceSnapshotService traceSnapshotService) {
    this.costRunPartItemMapper = costRunPartItemMapper;
    this.costRunCostItemMapper = costRunCostItemMapper;
    this.pricePrepareItemMapper = pricePrepareItemMapper;
    this.traceSnapshotService = traceSnapshotService;
  }

  @Override
  @Transactional
  public void writeQuoteResult(CostRunObjectResult result) {
    if (result == null || result.getContext() == null) {
      throw new IllegalArgumentException("成本结果和核算上下文不能为空");
    }
    String oaNo = required(result.getContext().getOaNo(), "OA 单号");
    String productCode = required(result.getContext().getProductCode(), "产品料号");
    if (result.getContext().getCostRunVersionId() == null
        || !StringUtils.hasText(result.getContext().getCostRunNo())) {
      throw new IllegalArgumentException("成本明细必须归属明确的成本版本");
    }

    overwritePartItems(result, oaNo, productCode);
    overwriteCostItems(result, oaNo, productCode);
    // 底稿和明细必须在同一事务写入，不能留下“有成本、无依据”的半套结果。
    traceSnapshotService.rebuildForVersion(traceVersion(result));
  }

  private void overwritePartItems(CostRunObjectResult result, String oaNo, String productCode) {
    String costRunNo = result.getContext().getCostRunNo().trim();
    costRunPartItemMapper.deleteQuoteItemsByCostRunNo(costRunNo);
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
      entity.setCostRunNo(costRunNo);
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
      entity.setRemark(truncateRemark(item.getRemark(), MAX_PART_REMARK_LENGTH));
      entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
      entity.setPriceOrgCode(trimToNull(item.getPriceOrgCode()));
      entity.setMaterialOrganizationCode(trimToNull(item.getMaterialOrganizationCode()));
      costRunPartItemMapper.insert(entity);
    }
  }

  private void overwriteCostItems(CostRunObjectResult result, String oaNo, String productCode) {
    String costRunNo = result.getContext().getCostRunNo().trim();
    costRunCostItemMapper.deleteQuoteItemsByCostRunNo(costRunNo);
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
      entity.setCostRunNo(costRunNo);
      entity.setProductCode(productCode);
      entity.setLineNo(lineNo++);
      entity.setCostCode(trimToNull(item.getCostCode()));
      entity.setCostName(trimToNull(item.getCostName()));
      entity.setBaseAmount(item.getBaseAmount());
      entity.setRate(item.getRate());
      entity.setAmount(item.getAmount());
      entity.setRemark(truncateRemark(item.getRemark(), MAX_COST_REMARK_LENGTH));
      entity.setCategory(
          StringUtils.hasText(item.getCategory())
              ? item.getCategory().trim()
              : CostItemCategory.EXPENSE);
      entity.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
      costRunCostItemMapper.insert(entity);
    }
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
    version.setBusinessUnitType(trimToNull(result.getContext().getBusinessUnitType()));
    return version;
  }

  private String required(String value, String label) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(label + "不能为空");
    }
    return value.trim();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }

  private String truncateRemark(String value, int maxLength) {
    String text = trimToNull(value);
    if (text == null || text.length() <= maxLength) {
      return text;
    }
    return text.substring(0, maxLength - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
  }

  private String firstText(String first, String second) {
    return StringUtils.hasText(first) ? first.trim() : trimToNull(second);
  }
}
