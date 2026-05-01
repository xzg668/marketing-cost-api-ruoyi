package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.service.PriceFixedItemService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceFixedItemServiceImpl implements PriceFixedItemService {
  private static final int DEFAULT_TAX_INCLUDED = 1;

  private final PriceFixedItemMapper itemMapper;

  public PriceFixedItemServiceImpl(PriceFixedItemMapper itemMapper) {
    this.itemMapper = itemMapper;
  }

  @Override
  public Page<PriceFixedItem> page(String materialCode, String supplierCode, String sourceType,
      String pricingMonth, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(PriceFixedItem.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceFixedItem::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(supplierCode)) {
      query.like(PriceFixedItem::getSupplierCode, supplierCode.trim());
    }
    // V46：来源类型 + 结算期间精确匹配（前端 tab + 月份选择器联动）
    if (StringUtils.hasText(sourceType)) {
      query.eq(PriceFixedItem::getSourceType, sourceType.trim());
    }
    if (StringUtils.hasText(pricingMonth)) {
      query.eq(PriceFixedItem::getPricingMonth, pricingMonth.trim());
    }
    query.orderByDesc(PriceFixedItem::getId);
    Page<PriceFixedItem> pager = new Page<>(page, pageSize);
    return itemMapper.selectPage(pager, query);
  }

  @Override
  public PriceFixedItem create(PriceFixedItemUpdateRequest request) {
    if (request == null) {
      return null;
    }
    PriceFixedItem item = new PriceFixedItem();
    merge(item, request);
    fillDefaults(item);
    if (!StringUtils.hasText(item.getMaterialCode()) || item.getFixedPrice() == null) {
      return null;
    }
    itemMapper.insert(item);
    return item;
  }

  @Override
  public PriceFixedItem update(Long id, PriceFixedItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceFixedItem existing = itemMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    itemMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && itemMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceFixedItem> importItems(PriceFixedItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<PriceFixedItem> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null
          || !StringUtils.hasText(row.getMaterialCode())
          || row.getFixedPrice() == null) {
        continue;
      }
      PriceFixedItem item = findExisting(row);
      if (item == null) {
        item = new PriceFixedItem();
        fillItem(item, row);
        fillDefaults(item);
        itemMapper.insert(item);
      } else {
        fillItem(item, row);
        fillDefaults(item);
        itemMapper.updateById(item);
      }
      imported.add(item);
    }
    return imported;
  }

  /**
   * V46：去重锚点对齐新 UK = (material_code, supplier_code, business_unit_type, source_type, pricing_month)
   *
   * <p>导入时按 source_type + pricing_month 区分同料号不同来源 / 不同月份的行，避免 UK 冲突。
   * 老 effective_from 字段保留但不再作为去重维度（由 pricing_month 替代）。
   */
  private PriceFixedItem findExisting(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    var query = Wrappers.lambdaQuery(PriceFixedItem.class)
        .eq(PriceFixedItem::getMaterialCode, row.getMaterialCode().trim());
    String supplierCode = trimToNull(row.getSupplierCode());
    if (supplierCode == null) {
      query.isNull(PriceFixedItem::getSupplierCode);
    } else {
      query.eq(PriceFixedItem::getSupplierCode, supplierCode);
    }
    // V46：去掉 spec_model + effective_from 的去重，改用 source_type + pricing_month
    String sourceType = trimToNull(row.getSourceType());
    if (sourceType == null) {
      sourceType = "PURCHASE";   // 与 fillDefaults 默认值一致
    }
    query.eq(PriceFixedItem::getSourceType, sourceType);
    String pricingMonth = trimToNull(row.getPricingMonth());
    if (pricingMonth == null) {
      pricingMonth = "2026-03";
    }
    query.eq(PriceFixedItem::getPricingMonth, pricingMonth);
    return itemMapper.selectOne(query.last("LIMIT 1"));
  }

  private void fillItem(PriceFixedItem item,
      PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    item.setOrgCode(row.getOrgCode());
    item.setSourceName(row.getSourceName());
    item.setSupplierName(row.getSupplierName());
    item.setSupplierCode(trimToNull(row.getSupplierCode()));
    item.setPurchaseClass(row.getPurchaseClass());
    item.setMaterialName(row.getMaterialName());
    item.setMaterialCode(row.getMaterialCode());
    item.setSpecModel(trimToNull(row.getSpecModel()));
    item.setUnit(row.getUnit());
    item.setFormulaExpr(row.getFormulaExpr());
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setProcessFee(row.getProcessFee());
    item.setAgentFee(row.getAgentFee());
    item.setFixedPrice(row.getFixedPrice());
    if (row.getTaxIncluded() != null) {
      item.setTaxIncluded(row.getTaxIncluded() ? 1 : 0);
    }
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setOrderType(row.getOrderType());
    item.setQuota(row.getQuota());
    // V46 新字段
    if (row.getSourceType() != null) item.setSourceType(row.getSourceType());
    if (row.getProcessNo() != null) item.setProcessNo(row.getProcessNo());
    if (row.getPlannedPrice() != null) item.setPlannedPrice(row.getPlannedPrice());
    if (row.getMarkupRatio() != null) item.setMarkupRatio(row.getMarkupRatio());
    if (row.getRemark() != null) item.setRemark(row.getRemark());
    if (row.getPricingMonth() != null) item.setPricingMonth(row.getPricingMonth());
    // V47 SETTLE 双口径
    if (row.getBaseSettlePrice() != null) item.setBaseSettlePrice(row.getBaseSettlePrice());
    if (row.getLinkedSettlePrice() != null) item.setLinkedSettlePrice(row.getLinkedSettlePrice());
  }

  private void merge(PriceFixedItem item, PriceFixedItemUpdateRequest request) {
    if (request == null) {
      return;
    }
    if (request.getOrgCode() != null) {
      item.setOrgCode(request.getOrgCode());
    }
    if (request.getSourceName() != null) {
      item.setSourceName(request.getSourceName());
    }
    if (request.getSupplierName() != null) {
      item.setSupplierName(request.getSupplierName());
    }
    if (request.getSupplierCode() != null) {
      item.setSupplierCode(request.getSupplierCode());
    }
    if (request.getPurchaseClass() != null) {
      item.setPurchaseClass(request.getPurchaseClass());
    }
    if (request.getMaterialName() != null) {
      item.setMaterialName(request.getMaterialName());
    }
    if (request.getMaterialCode() != null) {
      item.setMaterialCode(request.getMaterialCode());
    }
    if (request.getSpecModel() != null) {
      item.setSpecModel(request.getSpecModel());
    }
    if (request.getUnit() != null) {
      item.setUnit(request.getUnit());
    }
    if (request.getFormulaExpr() != null) {
      item.setFormulaExpr(request.getFormulaExpr());
    }
    if (request.getBlankWeight() != null) {
      item.setBlankWeight(request.getBlankWeight());
    }
    if (request.getNetWeight() != null) {
      item.setNetWeight(request.getNetWeight());
    }
    if (request.getProcessFee() != null) {
      item.setProcessFee(request.getProcessFee());
    }
    if (request.getAgentFee() != null) {
      item.setAgentFee(request.getAgentFee());
    }
    if (request.getFixedPrice() != null) {
      item.setFixedPrice(request.getFixedPrice());
    }
    if (request.getTaxIncluded() != null) {
      item.setTaxIncluded(request.getTaxIncluded() ? 1 : 0);
    }
    if (request.getEffectiveFrom() != null) {
      item.setEffectiveFrom(request.getEffectiveFrom());
    }
    if (request.getEffectiveTo() != null) {
      item.setEffectiveTo(request.getEffectiveTo());
    }
    if (request.getOrderType() != null) {
      item.setOrderType(request.getOrderType());
    }
    if (request.getQuota() != null) {
      item.setQuota(request.getQuota());
    }
    // V46 新增字段
    if (request.getSourceType() != null) {
      item.setSourceType(request.getSourceType());
    }
    if (request.getProcessNo() != null) {
      item.setProcessNo(request.getProcessNo());
    }
    if (request.getPlannedPrice() != null) {
      item.setPlannedPrice(request.getPlannedPrice());
    }
    if (request.getMarkupRatio() != null) {
      item.setMarkupRatio(request.getMarkupRatio());
    }
    if (request.getRemark() != null) {
      item.setRemark(request.getRemark());
    }
    if (request.getPricingMonth() != null) {
      item.setPricingMonth(request.getPricingMonth());
    }
    // V47 SETTLE 双口径
    if (request.getBaseSettlePrice() != null) {
      item.setBaseSettlePrice(request.getBaseSettlePrice());
    }
    if (request.getLinkedSettlePrice() != null) {
      item.setLinkedSettlePrice(request.getLinkedSettlePrice());
    }
  }

  private void fillDefaults(PriceFixedItem item) {
    if (item.getTaxIncluded() == null) {
      item.setTaxIncluded(DEFAULT_TAX_INCLUDED);
    }
    if (StringUtils.hasText(item.getMaterialCode())) {
      item.setMaterialCode(item.getMaterialCode().trim());
    }
    if (StringUtils.hasText(item.getSupplierCode())) {
      item.setSupplierCode(item.getSupplierCode().trim());
    }
    if (StringUtils.hasText(item.getSpecModel())) {
      item.setSpecModel(item.getSpecModel().trim());
    }
    // V46 默认 source_type=PURCHASE / pricing_month=2026-03（兜住前端没传的场景）
    if (!StringUtils.hasText(item.getSourceType())) {
      item.setSourceType("PURCHASE");
    }
    if (!StringUtils.hasText(item.getPricingMonth())) {
      item.setPricingMonth("2026-03");
    }
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
