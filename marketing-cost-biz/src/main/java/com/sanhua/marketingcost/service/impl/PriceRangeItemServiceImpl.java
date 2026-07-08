package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceRangeItemImportRequest;
import com.sanhua.marketingcost.dto.PriceRangeItemImportResult;
import com.sanhua.marketingcost.dto.PriceRangeItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceRangeFactorRule;
import com.sanhua.marketingcost.entity.PriceRangeItem;
import com.sanhua.marketingcost.mapper.PriceRangeFactorRuleMapper;
import com.sanhua.marketingcost.mapper.PriceRangeItemMapper;
import com.sanhua.marketingcost.service.MaterialPriceTypeService;
import com.sanhua.marketingcost.service.PriceRangeItemService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceRangeItemServiceImpl implements PriceRangeItemService {
  private static final int DEFAULT_TAX_INCLUDED = 1;
  private static final String RANGE_BASIS_QTY = "QTY";
  private static final String RANGE_BASIS_FACTOR = "FACTOR";
  private static final DateTimeFormatter BATCH_TIME_FORMATTER =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

  private final PriceRangeItemMapper itemMapper;
  private final PriceRangeFactorRuleMapper factorRuleMapper;
  private final MaterialPriceTypeService materialPriceTypeService;

  public PriceRangeItemServiceImpl(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper) {
    this(itemMapper, factorRuleMapper, null);
  }

  @Autowired
  public PriceRangeItemServiceImpl(
      PriceRangeItemMapper itemMapper,
      PriceRangeFactorRuleMapper factorRuleMapper,
      MaterialPriceTypeService materialPriceTypeService) {
    this.itemMapper = itemMapper;
    this.factorRuleMapper = factorRuleMapper;
    this.materialPriceTypeService = materialPriceTypeService;
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
    closePreviousVersions(item);
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
    if (RANGE_BASIS_FACTOR.equals(normalizeRangeBasis(request.getRangeBasis()))) {
      return importFactorItems(request);
    }
    return importQtyItems(request);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public PriceRangeItemImportResult importItemsWithResult(PriceRangeItemImportRequest request) {
    List<PriceRangeItem> imported = importItems(request);
    if (request == null
        || !RANGE_BASIS_FACTOR.equals(normalizeRangeBasis(request.getRangeBasis()))
        || materialPriceTypeService == null) {
      return new PriceRangeItemImportResult(imported, List.of());
    }
    return new PriceRangeItemImportResult(
        imported,
        materialPriceTypeService.findRangePriceTypeConflicts(imported));
  }

  private List<PriceRangeItem> importQtyItems(PriceRangeItemImportRequest request) {
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

  private List<PriceRangeItem> importFactorItems(PriceRangeItemImportRequest request) {
    String requestFactorCode = upperTrimToNull(request.getFactorCode());
    String businessUnitType = trimToNull(request.getBusinessUnitType());
    String importBatchNo = trimToNull(request.getImportBatchNo());
    if (importBatchNo == null) {
      importBatchNo = "RANGE" + LocalDateTime.now().format(BATCH_TIME_FORMATTER);
    }

    Map<String, List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> rowsByMaterial =
        validateAndGroupFactorRows(request, requestFactorCode);
    if (rowsByMaterial.isEmpty()) {
      return List.of();
    }

    List<PriceRangeItem> imported = new ArrayList<>();
    for (var entry : rowsByMaterial.entrySet()) {
      String materialCode = entry.getKey();
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows = entry.getValue();
      String factorCode = resolveSingleFactorCode(rows, requestFactorCode, materialCode);
      LocalDate effectiveFrom = resolveEffectiveFrom(rows);
      PriceRangeItemImportRequest.PriceRangeItemImportRow first = rows.get(0);

      List<PriceRangeFactorRule> currentRules = findCurrentFactorRules(businessUnitType, materialCode);
      int nextVersion = currentRules.stream()
          .map(PriceRangeFactorRule::getVersionNo)
          .filter(v -> v != null)
          .max(Integer::compareTo)
          .orElse(0) + 1;

      PriceRangeFactorRule newRule = new PriceRangeFactorRule();
      newRule.setBusinessUnitType(businessUnitType);
      newRule.setMaterialCode(materialCode);
      newRule.setMaterialName(first.getMaterialName());
      newRule.setSpecModel(trimToNull(first.getSpecModel()));
      newRule.setFactorCode(factorCode);
      newRule.setFactorName(trimToNull(request.getFactorName()));
      newRule.setFactorUnit(trimToNull(request.getFactorUnit()));
      newRule.setPriceUnit(trimToNull(request.getPriceUnit()));
      newRule.setVersionNo(nextVersion);
      newRule.setImportBatchNo(importBatchNo);
      newRule.setSourceFile(trimToNull(request.getSourceFile()));
      newRule.setSourceSheet(trimToNull(request.getSourceSheet()));
      newRule.setEffectiveFrom(effectiveFrom);
      newRule.setEffectiveTo(null);
      newRule.setCurrentFlag(1);
      factorRuleMapper.insert(newRule);

      for (PriceRangeItemImportRequest.PriceRangeItemImportRow row : rows) {
        PriceRangeItem item = new PriceRangeItem();
        fillItem(item, row);
        fillDefaults(item);
        item.setBusinessUnitType(businessUnitType);
        item.setMaterialCode(materialCode);
        item.setRangeBasis(RANGE_BASIS_FACTOR);
        item.setFactorRuleId(newRule.getId());
        item.setFactorCode(factorCode);
        item.setImportBatchNo(importBatchNo);
        item.setCurrentFlag(1);
        item.setEffectiveFrom(effectiveFrom);
        item.setEffectiveTo(null);
        itemMapper.insert(item);
        imported.add(item);
      }

      expireCurrentFactorVersions(currentRules, effectiveFrom);
    }
    return imported;
  }

  private Map<String, List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> validateAndGroupFactorRows(
      PriceRangeItemImportRequest request,
      String requestFactorCode) {
    Map<String, List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> rowsByMaterial =
        new LinkedHashMap<>();
    for (var row : request.getRows()) {
      if (row == null) {
        throw new IllegalArgumentException("区间价导入存在空行");
      }
      String materialCode = trimToNull(row.getMaterialCode());
      if (materialCode == null) {
        throw new IllegalArgumentException("区间价导入存在物料代码为空的行");
      }
      if (row.getRangeLow() == null || row.getRangeHigh() == null) {
        throw new IllegalArgumentException("区间价导入存在区间上下限为空: " + materialCode);
      }
      if (row.getRangeLow().compareTo(row.getRangeHigh()) > 0) {
        throw new IllegalArgumentException("区间价导入区间下限大于上限: " + materialCode);
      }
      if (row.getPriceExclTax() == null && row.getPriceInclTax() == null) {
        throw new IllegalArgumentException("区间价导入价格为空: " + materialCode);
      }
      String rowFactorCode = resolveRowFactorCode(row, requestFactorCode);
      if (rowFactorCode == null) {
        throw new IllegalArgumentException("行情因素区间价缺少 factorCode: " + materialCode);
      }
      rowsByMaterial.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(row);
    }
    for (var entry : rowsByMaterial.entrySet()) {
      resolveSingleFactorCode(entry.getValue(), requestFactorCode, entry.getKey());
      validateNoOverlappingRanges(entry.getKey(), entry.getValue());
    }
    return rowsByMaterial;
  }

  private String resolveSingleFactorCode(
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows,
      String requestFactorCode,
      String materialCode) {
    String factorCode = null;
    for (var row : rows) {
      String rowFactorCode = resolveRowFactorCode(row, requestFactorCode);
      if (factorCode == null) {
        factorCode = rowFactorCode;
      } else if (!factorCode.equals(rowFactorCode)) {
        throw new IllegalArgumentException("同一物料同一批次存在多个 factorCode: " + materialCode);
      }
    }
    return factorCode;
  }

  private void validateNoOverlappingRanges(
      String materialCode,
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows) {
    List<PriceRangeItemImportRequest.PriceRangeItemImportRow> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator
        .comparing(PriceRangeItemImportRequest.PriceRangeItemImportRow::getRangeLow)
        .thenComparing(PriceRangeItemImportRequest.PriceRangeItemImportRow::getRangeHigh));
    for (int i = 1; i < sorted.size(); i += 1) {
      var previous = sorted.get(i - 1);
      var current = sorted.get(i);
      if (previous.getRangeHigh().compareTo(current.getRangeLow()) >= 0) {
        throw new IllegalArgumentException("同一物料同一批次区间重叠: " + materialCode);
      }
    }
  }

  private LocalDate resolveEffectiveFrom(
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows) {
    for (var row : rows) {
      if (row.getEffectiveFrom() != null) {
        return row.getEffectiveFrom();
      }
    }
    return LocalDate.now();
  }

  private List<PriceRangeFactorRule> findCurrentFactorRules(
      String businessUnitType,
      String materialCode) {
    var query = Wrappers.lambdaQuery(PriceRangeFactorRule.class)
        .eq(PriceRangeFactorRule::getMaterialCode, materialCode)
        .eq(PriceRangeFactorRule::getCurrentFlag, 1);
    if (businessUnitType == null) {
      query.isNull(PriceRangeFactorRule::getBusinessUnitType);
    } else {
      query.eq(PriceRangeFactorRule::getBusinessUnitType, businessUnitType);
    }
    List<PriceRangeFactorRule> rows = factorRuleMapper.selectList(query);
    return rows == null ? List.of() : rows;
  }

  private void expireCurrentFactorVersions(List<PriceRangeFactorRule> currentRules, LocalDate effectiveFrom) {
    if (currentRules == null || currentRules.isEmpty()) {
      return;
    }
    for (PriceRangeFactorRule rule : currentRules) {
      rule.setCurrentFlag(0);
      rule.setEffectiveTo(effectiveFrom);
      factorRuleMapper.updateById(rule);
      if (rule.getId() == null) {
        continue;
      }
      List<PriceRangeItem> oldItems =
          itemMapper.selectList(
              Wrappers.lambdaQuery(PriceRangeItem.class)
                  .eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_FACTOR)
                  .eq(PriceRangeItem::getFactorRuleId, rule.getId())
                  .eq(PriceRangeItem::getCurrentFlag, 1));
      if (oldItems == null) {
        continue;
      }
      for (PriceRangeItem oldItem : oldItems) {
        oldItem.setCurrentFlag(0);
        oldItem.setEffectiveTo(effectiveFrom);
        itemMapper.updateById(oldItem);
      }
    }
  }

  private PriceRangeItem findExisting(PriceRangeItemImportRequest.PriceRangeItemImportRow row) {
    var query = Wrappers.lambdaQuery(PriceRangeItem.class)
        .eq(PriceRangeItem::getMaterialCode, row.getMaterialCode().trim())
        .eq(PriceRangeItem::getRangeLow, row.getRangeLow())
        .eq(PriceRangeItem::getRangeHigh, row.getRangeHigh())
        .and(q -> q.eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_QTY)
            .or()
            .isNull(PriceRangeItem::getRangeBasis));
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
    if (item.getEffectiveFrom() == null) {
      item.setEffectiveFrom(LocalDate.now());
    }
    item.setRangeBasis(normalizeRangeBasis(item.getRangeBasis()));
    if (item.getCurrentFlag() == null) {
      item.setCurrentFlag(1);
    }
  }

  private void closePreviousVersions(PriceRangeItem item) {
    if (item == null || item.getEffectiveFrom() == null || !StringUtils.hasText(item.getMaterialCode())) {
      return;
    }
    var query = Wrappers.lambdaQuery(PriceRangeItem.class)
        .eq(PriceRangeItem::getMaterialCode, item.getMaterialCode())
        .eq(PriceRangeItem::getRangeLow, item.getRangeLow())
        .eq(PriceRangeItem::getRangeHigh, item.getRangeHigh())
        .and(q -> q.isNull(PriceRangeItem::getEffectiveTo)
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

  private String upperTrimToNull(String value) {
    String normalized = trimToNull(value);
    return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
  }

  private String normalizeRangeBasis(String value) {
    String normalized = trimToNull(value);
    if (normalized == null) {
      return RANGE_BASIS_QTY;
    }
    return normalized.toUpperCase(Locale.ROOT);
  }

  private String resolveRowFactorCode(
      PriceRangeItemImportRequest.PriceRangeItemImportRow row,
      String requestFactorCode) {
    String rowFactorCode = trimToNull(row.getFactorCode());
    return rowFactorCode == null ? requestFactorCode : rowFactorCode.toUpperCase(Locale.ROOT);
  }
}
