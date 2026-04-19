package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceRangeItemServiceImpl implements PriceRangeItemService {
  private static final int DEFAULT_TAX_INCLUDED = 1;

  private final PriceRangeItemMapper itemMapper;

  public PriceRangeItemServiceImpl(PriceRangeItemMapper itemMapper) {
    this.itemMapper = itemMapper;
  }

  @Override
  public Page<PriceRangeItem> page(String materialCode, String supplierCode, String specModel,
      String effectiveFrom, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(PriceRangeItem.class);
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceRangeItem::getMaterialCode, materialCode.trim());
    }
    if (StringUtils.hasText(supplierCode)) {
      query.like(PriceRangeItem::getSupplierCode, supplierCode.trim());
    }
    if (StringUtils.hasText(specModel)) {
      query.like(PriceRangeItem::getSpecModel, specModel.trim());
    }
    if (StringUtils.hasText(effectiveFrom)) {
      String ym = effectiveFrom.trim();
      LocalDate start = LocalDate.parse(ym + "-01");
      LocalDate end = start.plusMonths(1).minusDays(1);
      query.ge(PriceRangeItem::getEffectiveFrom, start);
      query.le(PriceRangeItem::getEffectiveFrom, end);
    }
    query.orderByDesc(PriceRangeItem::getId);
    Page<PriceRangeItem> pager = new Page<>(page, pageSize);
    return itemMapper.selectPage(pager, query);
  }

  @Override
  public PriceRangeItem create(PriceRangeItemUpdateRequest request) {
    if (request == null) {
      return null;
    }
    PriceRangeItem item = new PriceRangeItem();
    merge(item, request);
    fillDefaults(item);
    if (!StringUtils.hasText(item.getMaterialCode())
        || item.getRangeLow() == null || item.getRangeHigh() == null) {
      return null;
    }
    if (item.getPriceExclTax() == null && item.getPriceInclTax() == null) {
      return null;
    }
    itemMapper.insert(item);
    return item;
  }

  @Override
  public PriceRangeItem update(Long id, PriceRangeItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceRangeItem existing = itemMapper.selectById(id);
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
  public List<PriceRangeItem> importItems(PriceRangeItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<PriceRangeItem> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      if (row.getRangeLow() == null || row.getRangeHigh() == null) {
        continue;
      }
      if (row.getPriceExclTax() == null && row.getPriceInclTax() == null) {
        continue;
      }
      PriceRangeItem item = findExisting(row);
      if (item == null) {
        item = new PriceRangeItem();
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

  private PriceRangeItem findExisting(PriceRangeItemImportRequest.PriceRangeItemImportRow row) {
    var query = Wrappers.lambdaQuery(PriceRangeItem.class)
        .eq(PriceRangeItem::getMaterialCode, row.getMaterialCode().trim())
        .eq(PriceRangeItem::getRangeLow, row.getRangeLow())
        .eq(PriceRangeItem::getRangeHigh, row.getRangeHigh());
    String supplierCode = trimToNull(row.getSupplierCode());
    if (supplierCode == null) {
      query.isNull(PriceRangeItem::getSupplierCode);
    } else {
      query.eq(PriceRangeItem::getSupplierCode, supplierCode);
    }
    String specModel = trimToNull(row.getSpecModel());
    if (specModel == null) {
      query.isNull(PriceRangeItem::getSpecModel);
    } else {
      query.eq(PriceRangeItem::getSpecModel, specModel);
    }
    LocalDate effectiveFrom = row.getEffectiveFrom();
    if (effectiveFrom == null) {
      query.isNull(PriceRangeItem::getEffectiveFrom);
    } else {
      query.eq(PriceRangeItem::getEffectiveFrom, effectiveFrom);
    }
    return itemMapper.selectOne(query.last("LIMIT 1"));
  }

  private void fillItem(PriceRangeItem item,
      PriceRangeItemImportRequest.PriceRangeItemImportRow row) {
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
    item.setRangeLow(row.getRangeLow());
    item.setRangeHigh(row.getRangeHigh());
    item.setPriceExclTax(row.getPriceExclTax());
    item.setPriceInclTax(row.getPriceInclTax());
    if (row.getTaxIncluded() != null) {
      item.setTaxIncluded(row.getTaxIncluded() ? 1 : 0);
    }
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setOrderType(row.getOrderType());
    item.setQuota(row.getQuota());
  }

  private void merge(PriceRangeItem item, PriceRangeItemUpdateRequest req) {
    if (req == null) return;
    if (req.getOrgCode() != null) item.setOrgCode(req.getOrgCode());
    if (req.getSourceName() != null) item.setSourceName(req.getSourceName());
    if (req.getSupplierName() != null) item.setSupplierName(req.getSupplierName());
    if (req.getSupplierCode() != null) item.setSupplierCode(req.getSupplierCode());
    if (req.getPurchaseClass() != null) item.setPurchaseClass(req.getPurchaseClass());
    if (req.getMaterialName() != null) item.setMaterialName(req.getMaterialName());
    if (req.getMaterialCode() != null) item.setMaterialCode(req.getMaterialCode());
    if (req.getSpecModel() != null) item.setSpecModel(req.getSpecModel());
    if (req.getUnit() != null) item.setUnit(req.getUnit());
    if (req.getFormulaExpr() != null) item.setFormulaExpr(req.getFormulaExpr());
    if (req.getBlankWeight() != null) item.setBlankWeight(req.getBlankWeight());
    if (req.getNetWeight() != null) item.setNetWeight(req.getNetWeight());
    if (req.getProcessFee() != null) item.setProcessFee(req.getProcessFee());
    if (req.getAgentFee() != null) item.setAgentFee(req.getAgentFee());
    if (req.getRangeLow() != null) item.setRangeLow(req.getRangeLow());
    if (req.getRangeHigh() != null) item.setRangeHigh(req.getRangeHigh());
    if (req.getPriceExclTax() != null) item.setPriceExclTax(req.getPriceExclTax());
    if (req.getPriceInclTax() != null) item.setPriceInclTax(req.getPriceInclTax());
    if (req.getTaxIncluded() != null) item.setTaxIncluded(req.getTaxIncluded() ? 1 : 0);
    if (req.getEffectiveFrom() != null) item.setEffectiveFrom(req.getEffectiveFrom());
    if (req.getEffectiveTo() != null) item.setEffectiveTo(req.getEffectiveTo());
    if (req.getOrderType() != null) item.setOrderType(req.getOrderType());
    if (req.getQuota() != null) item.setQuota(req.getQuota());
  }

  private void fillDefaults(PriceRangeItem item) {
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
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }
}
