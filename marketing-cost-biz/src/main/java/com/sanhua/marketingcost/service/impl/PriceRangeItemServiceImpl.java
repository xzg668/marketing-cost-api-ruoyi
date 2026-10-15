package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class PriceRangeItemServiceImpl implements PriceRangeItemService {
  private static final int DEFAULT_TAX_INCLUDED = 1;
  private static final String RANGE_BASIS_QTY = "QTY";
  private static final String RANGE_BASIS_FACTOR = "FACTOR";

  private final PriceRangeItemMapper itemMapper;
  private final PriceRangeFactorRuleMapper factorRuleMapper;

  public PriceRangeItemServiceImpl(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper) {
    this.itemMapper = itemMapper;
    this.factorRuleMapper = factorRuleMapper;
  }

  @Override
  public Page<PriceRangeItem> page(
      String materialCode,
      String supplierCode,
      String specModel,
      String effectiveFrom,
      int page,
      int pageSize) {
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
    Page<PriceRangeItem> result = itemMapper.selectPage(pager, query);
    var ruleIds =
        result.getRecords().stream()
            .map(PriceRangeItem::getFactorRuleId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    if (!ruleIds.isEmpty()) {
      var units = new java.util.HashMap<Long, String>();
      for (var rule :
          factorRuleMapper.selectList(
              Wrappers.lambdaQuery(PriceRangeFactorRule.class)
                  .select(PriceRangeFactorRule::getId, PriceRangeFactorRule::getFactorUnit)
                  .in(PriceRangeFactorRule::getId, ruleIds)))
        units.put(rule.getId(), rule.getFactorUnit());
      result.getRecords().forEach(row -> row.setFactorUnit(units.get(row.getFactorRuleId())));
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceRangeItem create(PriceRangeItemUpdateRequest request) {
    if (request == null) {
      return null;
    }
    PriceRangeItem item = new PriceRangeItem();
    merge(item, request);
    fillDefaults(item);
    if (!StringUtils.hasText(item.getMaterialCode())
        || item.getRangeLow() == null
        || item.getRangeHigh() == null) {
      return null;
    }
    if (item.getPriceExclTax() == null && item.getPriceInclTax() == null) {
      return null;
    }
    closePreviousVersions(item);
    itemMapper.insert(item);
    return item;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceRangeItem update(Long id, PriceRangeItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceRangeItem existing = itemMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    requireQuantity(existing);
    merge(existing, request);
    fillDefaults(existing);
    itemMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    if (id == null) return false;
    PriceRangeItem existing = itemMapper.selectById(id);
    if (existing == null) return false;
    requireQuantity(existing);
    return itemMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceRangeItem> importItems(PriceRangeItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    if (!RANGE_BASIS_QTY.equals(normalizeRangeBasis(request.getRangeBasis()))
        || request.getRows().stream()
            .filter(java.util.Objects::nonNull)
            .anyMatch(row -> !RANGE_BASIS_QTY.equals(normalizeRangeBasis(row.getRangeBasis())))) {
      throw new IllegalArgumentException("行情区间必须上传完整原稿，经后端预检后发布");
    }
    List<PriceRangeItem> imported = importQtyItems(request);
    return imported;
  }

  private List<PriceRangeItem> importQtyItems(PriceRangeItemImportRequest request) {
    List<PriceRangeItem> imported = new ArrayList<>();
    String businessUnitType = trimToNull(request.getBusinessUnitType());
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
      PriceRangeItem item = findExisting(row, businessUnitType);
      if (item == null) {
        item = new PriceRangeItem();
        fillItem(item, row);
        fillDefaults(item);
        item.setBusinessUnitType(businessUnitType);
        item.setRangeBasis(RANGE_BASIS_QTY);
        item.setFactorRuleId(null);
        item.setFactorCode(null);
        item.setImportBatchNo(null);
        item.setCurrentFlag(1);
        closePreviousVersions(item);
        itemMapper.insert(item);
      } else {
        fillItem(item, row);
        fillDefaults(item);
        if (businessUnitType != null) {
          item.setBusinessUnitType(businessUnitType);
        }
        item.setRangeBasis(RANGE_BASIS_QTY);
        item.setFactorRuleId(null);
        item.setFactorCode(null);
        item.setCurrentFlag(item.getCurrentFlag() == null ? 1 : item.getCurrentFlag());
        itemMapper.updateById(item);
      }
      imported.add(item);
    }
    return imported;
  }

  private PriceRangeItem findExisting(
      PriceRangeItemImportRequest.PriceRangeItemImportRow row, String businessUnitType) {
    var query =
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getMaterialCode, row.getMaterialCode().trim())
            .eq(PriceRangeItem::getRangeLow, row.getRangeLow())
            .eq(PriceRangeItem::getRangeHigh, row.getRangeHigh())
            .and(
                q ->
                    q.eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_QTY)
                        .or()
                        .isNull(PriceRangeItem::getRangeBasis));
    if (businessUnitType != null) {
      query.eq(PriceRangeItem::getBusinessUnitType, businessUnitType);
    }
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

  private void fillItem(
      PriceRangeItem item, PriceRangeItemImportRequest.PriceRangeItemImportRow row) {
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
    if (item.getEffectiveFrom() == null) {
      item.setEffectiveFrom(LocalDate.now());
    }
    item.setRangeBasis(normalizeRangeBasis(item.getRangeBasis()));
    if (item.getCurrentFlag() == null) {
      item.setCurrentFlag(1);
    }
  }

  private void requireQuantity(PriceRangeItem item) {
    if (RANGE_BASIS_FACTOR.equals(item.getRangeBasis()) || item.getFactorRuleId() != null)
      throw new IllegalArgumentException("行情档位必须通过新原稿修订，不能单独编辑或删除");
  }

  private void closePreviousVersions(PriceRangeItem item) {
    if (item == null
        || item.getEffectiveFrom() == null
        || !StringUtils.hasText(item.getMaterialCode())) {
      return;
    }
    var query =
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getMaterialCode, item.getMaterialCode())
            .eq(PriceRangeItem::getRangeLow, item.getRangeLow())
            .eq(PriceRangeItem::getRangeHigh, item.getRangeHigh())
            .and(
                q ->
                    q.eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_QTY)
                        .or()
                        .isNull(PriceRangeItem::getRangeBasis))
            .eq(
                item.getBusinessUnitType() != null,
                PriceRangeItem::getBusinessUnitType,
                item.getBusinessUnitType())
            .eq(item.getOrgCode() != null, PriceRangeItem::getOrgCode, item.getOrgCode())
            .and(
                q ->
                    q.isNull(PriceRangeItem::getEffectiveTo)
                        .or()
                        .gt(PriceRangeItem::getEffectiveTo, item.getEffectiveFrom()));
    String supplierCode = trimToNull(item.getSupplierCode());
    if (supplierCode == null) {
      query.isNull(PriceRangeItem::getSupplierCode);
    } else {
      query.eq(PriceRangeItem::getSupplierCode, supplierCode);
    }
    String specModel = trimToNull(item.getSpecModel());
    if (specModel == null) {
      query.isNull(PriceRangeItem::getSpecModel);
    } else {
      query.eq(PriceRangeItem::getSpecModel, specModel);
    }
    for (PriceRangeItem row : itemMapper.selectList(query)) {
      if (item.getId() != null && item.getId().equals(row.getId())) {
        continue;
      }
      row.setEffectiveTo(item.getEffectiveFrom());
      itemMapper.updateById(row);
    }
  }



  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String normalizeRangeBasis(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return RANGE_BASIS_QTY;
    }
    return normalized.toUpperCase(Locale.ROOT);
  }
}
