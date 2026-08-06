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
import com.sanhua.marketingcost.util.SupplierSupplyRatioNormalizeUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    List<PendingFactorVersionExpiration> pendingExpirations = new ArrayList<>();
    for (var entry : rowsByMaterial.entrySet()) {
      String materialCode = entry.getKey();
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows = entry.getValue();
      String factorCode = resolveSingleFactorCode(rows, requestFactorCode, materialCode);
      LocalDate effectiveFrom = resolveEffectiveFrom(rows);
      PriceRangeItemImportRequest.PriceRangeItemImportRow first = rows.get(0);

      List<PriceRangeFactorRule> currentRules = findCurrentFactorRules(businessUnitType, materialCode);
      List<PriceRangeItem> currentItems = findCurrentFactorItems(currentRules);
      List<PriceRangeItem> mergeableCurrentItems = filterCurrentItemsForFactor(
          currentRules,
          currentItems,
          factorCode);
      List<PriceRangeItem> desiredItems = buildMergedFactorItems(
          rows,
          mergeableCurrentItems,
          businessUnitType,
          materialCode,
          factorCode,
          importBatchNo);
      if (isIdenticalCurrentVersion(currentRules, currentItems, factorCode, desiredItems)) {
        imported.addAll(currentItems);
        continue;
      }
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

      for (PriceRangeItem item : desiredItems) {
        item.setFactorRuleId(newRule.getId());
        item.setCurrentFlag(1);
        itemMapper.insert(item);
        imported.add(item);
      }
      pendingExpirations.add(new PendingFactorVersionExpiration(
          currentRules,
          currentItems,
          effectiveFrom));
    }
    for (PendingFactorVersionExpiration pending : pendingExpirations) {
      expireCurrentFactorVersions(
          pending.currentRules(),
          pending.currentItems(),
          pending.effectiveFrom());
    }
    return imported;
  }

  private List<PriceRangeItem> buildMergedFactorItems(
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows,
      List<PriceRangeItem> currentItems,
      String businessUnitType,
      String materialCode,
      String factorCode,
      String importBatchNo) {
    Set<String> replacedIdentities = resolveReplacedSupplierIdentities(
        rows,
        currentItems,
        materialCode);
    List<PriceRangeItem> merged = new ArrayList<>();
    for (PriceRangeItemImportRequest.PriceRangeItemImportRow row : rows) {
      PriceRangeItem item = new PriceRangeItem();
      fillItem(item, row);
      fillDefaults(item);
      item.setBusinessUnitType(businessUnitType);
      item.setMaterialCode(materialCode);
      item.setRangeBasis(RANGE_BASIS_FACTOR);
      item.setFactorRuleId(null);
      item.setFactorCode(factorCode);
      item.setImportBatchNo(importBatchNo);
      item.setCurrentFlag(1);
      merged.add(item);
    }
    for (PriceRangeItem currentItem : currentItems) {
      if (!replacedIdentities.contains(resolveSupplierIdentity(currentItem))) {
        merged.add(copyFactorItem(currentItem));
      }
    }
    return merged;
  }

  private Set<String> resolveReplacedSupplierIdentities(
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> incomingRows,
      List<PriceRangeItem> currentItems,
      String materialCode) {
    Set<String> currentIdentities = new HashSet<>();
    for (PriceRangeItem currentItem : currentItems) {
      currentIdentities.add(resolveSupplierIdentity(currentItem));
    }
    Set<String> replaced = new LinkedHashSet<>();
    for (PriceRangeItemImportRequest.PriceRangeItemImportRow incoming : incomingRows) {
      String incomingIdentity = resolveSupplierIdentity(incoming);
      replaced.add(incomingIdentity);
      if (!incomingIdentity.startsWith("CODE:") || currentIdentities.contains(incomingIdentity)) {
        continue;
      }
      String normalizedName = normalizeSupplierIdentityPart(incoming.getSupplierName());
      if (normalizedName == null) {
        continue;
      }
      Set<String> nameCandidates = new LinkedHashSet<>();
      for (PriceRangeItem currentItem : currentItems) {
        if (normalizedName.equals(normalizeSupplierIdentityPart(currentItem.getSupplierName()))) {
          nameCandidates.add(resolveSupplierIdentity(currentItem));
        }
      }
      if (nameCandidates.size() > 1) {
        throw new IllegalArgumentException(
            "供应商名称身份不唯一，无法升级供应商代码: 料号=" + materialCode
                + ", 供应商名称=" + incoming.getSupplierName()
                + ", 候选身份=" + nameCandidates);
      }
      if (nameCandidates.size() == 1) {
        String candidate = nameCandidates.iterator().next();
        if (candidate.startsWith("NAME:")) {
          replaced.add(candidate);
        }
      }
    }
    return replaced;
  }

  private List<PriceRangeItem> filterCurrentItemsForFactor(
      List<PriceRangeFactorRule> currentRules,
      List<PriceRangeItem> currentItems,
      String factorCode) {
    Set<Long> matchingRuleIds = new HashSet<>();
    for (PriceRangeFactorRule rule : currentRules) {
      if (Objects.equals(factorCode, upperTrimToNull(rule.getFactorCode()))
          && rule.getId() != null) {
        matchingRuleIds.add(rule.getId());
      }
    }
    if (matchingRuleIds.isEmpty()) {
      return List.of();
    }
    return currentItems.stream()
        .filter(item -> matchingRuleIds.contains(item.getFactorRuleId()))
        .toList();
  }

  private List<PriceRangeItem> findCurrentFactorItems(List<PriceRangeFactorRule> currentRules) {
    if (currentRules == null || currentRules.isEmpty()) {
      return List.of();
    }
    List<Long> ruleIds = currentRules.stream()
        .map(PriceRangeFactorRule::getId)
        .filter(Objects::nonNull)
        .toList();
    if (ruleIds.isEmpty()) {
      return List.of();
    }
    List<PriceRangeItem> rows = itemMapper.selectList(
        Wrappers.lambdaQuery(PriceRangeItem.class)
            .eq(PriceRangeItem::getRangeBasis, RANGE_BASIS_FACTOR)
            .in(PriceRangeItem::getFactorRuleId, ruleIds)
            .eq(PriceRangeItem::getCurrentFlag, 1));
    return rows == null ? List.of() : rows;
  }

  private boolean isIdenticalCurrentVersion(
      List<PriceRangeFactorRule> currentRules,
      List<PriceRangeItem> currentItems,
      String factorCode,
      List<PriceRangeItem> desiredItems) {
    if (currentRules == null || currentRules.size() != 1) {
      return false;
    }
    PriceRangeFactorRule currentRule = currentRules.get(0);
    if (!Objects.equals(factorCode, upperTrimToNull(currentRule.getFactorCode()))) {
      return false;
    }
    return factorItemSignatures(currentItems).equals(factorItemSignatures(desiredItems));
  }

  private Map<FactorItemBusinessSignature, Integer> factorItemSignatures(
      List<PriceRangeItem> items) {
    Map<FactorItemBusinessSignature, Integer> signatures = new HashMap<>();
    for (PriceRangeItem item : items) {
      FactorItemBusinessSignature signature = new FactorItemBusinessSignature(
          resolveSupplierIdentity(item),
          normalizeSupplierIdentityPart(item.getSupplierName()),
          item.getEffectiveFrom(),
          item.getEffectiveTo(),
          normalizeDecimal(item.getRangeLow()),
          normalizeDecimal(item.getRangeHigh()),
          normalizeDecimal(item.getPriceExclTax()),
          normalizeDecimal(item.getPriceInclTax()),
          item.getTaxIncluded());
      signatures.merge(signature, 1, Integer::sum);
    }
    return signatures;
  }

  private BigDecimal normalizeDecimal(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  private PriceRangeItem copyFactorItem(PriceRangeItem source) {
    PriceRangeItem target = new PriceRangeItem();
    target.setBusinessUnitType(source.getBusinessUnitType());
    target.setOrgCode(source.getOrgCode());
    target.setSourceName(source.getSourceName());
    target.setSupplierName(source.getSupplierName());
    target.setSupplierCode(source.getSupplierCode());
    target.setPurchaseClass(source.getPurchaseClass());
    target.setMaterialName(source.getMaterialName());
    target.setMaterialCode(source.getMaterialCode());
    target.setSpecModel(source.getSpecModel());
    target.setUnit(source.getUnit());
    target.setFormulaExpr(source.getFormulaExpr());
    target.setBlankWeight(source.getBlankWeight());
    target.setNetWeight(source.getNetWeight());
    target.setProcessFee(source.getProcessFee());
    target.setAgentFee(source.getAgentFee());
    target.setRangeLow(source.getRangeLow());
    target.setRangeHigh(source.getRangeHigh());
    target.setRangeBasis(RANGE_BASIS_FACTOR);
    target.setFactorRuleId(null);
    target.setFactorCode(source.getFactorCode());
    target.setImportBatchNo(source.getImportBatchNo());
    target.setCurrentFlag(1);
    target.setPriceExclTax(source.getPriceExclTax());
    target.setPriceInclTax(source.getPriceInclTax());
    target.setTaxIncluded(source.getTaxIncluded());
    target.setEffectiveFrom(source.getEffectiveFrom());
    target.setEffectiveTo(source.getEffectiveTo());
    target.setOrderType(source.getOrderType());
    target.setQuota(source.getQuota());
    return target;
  }

  private Map<String, List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> validateAndGroupFactorRows(
      PriceRangeItemImportRequest request,
      String requestFactorCode) {
    Map<String, List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> rowsByMaterial =
        new LinkedHashMap<>();
    Map<FactorRangeValidationGroup,
        List<PriceRangeItemImportRequest.PriceRangeItemImportRow>> rowsByValidationGroup =
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
      if (row.getEffectiveFrom() == null) {
        throw new IllegalArgumentException("行情因素区间价缺少生效日期: " + materialCode);
      }
      if (row.getEffectiveTo() != null
          && row.getEffectiveTo().isBefore(row.getEffectiveFrom())) {
        throw new IllegalArgumentException("行情因素区间价失效日期早于生效日期: " + materialCode);
      }
      String rowFactorCode = resolveRowFactorCode(row, requestFactorCode);
      if (rowFactorCode == null) {
        throw new IllegalArgumentException("行情因素区间价缺少 factorCode: " + materialCode);
      }
      rowsByMaterial.computeIfAbsent(materialCode, ignored -> new ArrayList<>()).add(row);
      FactorRangeValidationGroup validationGroup = new FactorRangeValidationGroup(
          materialCode,
          resolveSupplierIdentity(row),
          rowFactorCode);
      rowsByValidationGroup
          .computeIfAbsent(validationGroup, ignored -> new ArrayList<>())
          .add(row);
    }
    for (var entry : rowsByValidationGroup.entrySet()) {
      FactorRangeValidationGroup group = entry.getKey();
      validateNoOverlappingRanges(
          group.materialCode(),
          group.supplierIdentity(),
          group.factorCode(),
          entry.getValue());
      validateConsistentSupplierDates(group, entry.getValue());
    }
    for (var entry : rowsByMaterial.entrySet()) {
      resolveSingleFactorCode(entry.getValue(), requestFactorCode, entry.getKey());
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
      String supplierIdentity,
      String factorCode,
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows) {
    List<PriceRangeItemImportRequest.PriceRangeItemImportRow> sorted = new ArrayList<>(rows);
    sorted.sort(Comparator
        .comparing(PriceRangeItemImportRequest.PriceRangeItemImportRow::getRangeLow)
        .thenComparing(PriceRangeItemImportRequest.PriceRangeItemImportRow::getRangeHigh));
    for (int i = 1; i < sorted.size(); i += 1) {
      var previous = sorted.get(i - 1);
      var current = sorted.get(i);
      if (previous.getRangeHigh().compareTo(current.getRangeLow()) >= 0) {
        throw new IllegalArgumentException(
            "同一物料同一供应商同一影响因素区间重叠: 料号=" + materialCode
                + ", 供应商身份=" + supplierIdentity
                + ", factorCode=" + factorCode);
      }
    }
  }

  private void validateConsistentSupplierDates(
      FactorRangeValidationGroup group,
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows) {
    LocalDate effectiveFrom = rows.get(0).getEffectiveFrom();
    LocalDate effectiveTo = rows.get(0).getEffectiveTo();
    for (int index = 1; index < rows.size(); index += 1) {
      PriceRangeItemImportRequest.PriceRangeItemImportRow row = rows.get(index);
      if (!Objects.equals(effectiveFrom, row.getEffectiveFrom())
          || !Objects.equals(effectiveTo, row.getEffectiveTo())) {
        throw new IllegalArgumentException(
            "同一物料同一供应商同一影响因素的有效期不一致: 料号=" + group.materialCode()
                + ", 供应商身份=" + group.supplierIdentity()
                + ", factorCode=" + group.factorCode());
      }
    }
  }

  private String resolveSupplierIdentity(
      PriceRangeItemImportRequest.PriceRangeItemImportRow row) {
    return resolveSupplierIdentity(row.getSupplierCode(), row.getSupplierName());
  }

  private String resolveSupplierIdentity(PriceRangeItem item) {
    return resolveSupplierIdentity(item.getSupplierCode(), item.getSupplierName());
  }

  private String resolveSupplierIdentity(String rawSupplierCode, String rawSupplierName) {
    String supplierCode = normalizeSupplierIdentityPart(rawSupplierCode);
    if (supplierCode != null) {
      return "CODE:" + supplierCode;
    }
    String supplierName = normalizeSupplierIdentityPart(rawSupplierName);
    if (supplierName != null) {
      return "NAME:" + supplierName;
    }
    return "LEGACY";
  }

  private String normalizeSupplierIdentityPart(String value) {
    String normalized = SupplierSupplyRatioNormalizeUtils.normalizeKeyPart(value);
    return StringUtils.hasText(normalized)
        ? normalized.toUpperCase(Locale.ROOT)
        : null;
  }

  private record FactorRangeValidationGroup(
      String materialCode,
      String supplierIdentity,
      String factorCode) {}

  private LocalDate resolveEffectiveFrom(
      List<PriceRangeItemImportRequest.PriceRangeItemImportRow> rows) {
    return rows.stream()
        .map(PriceRangeItemImportRequest.PriceRangeItemImportRow::getEffectiveFrom)
        .filter(Objects::nonNull)
        .min(LocalDate::compareTo)
        .orElseThrow(() -> new IllegalArgumentException("行情因素区间价缺少生效日期"));
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

  private void expireCurrentFactorVersions(
      List<PriceRangeFactorRule> currentRules,
      List<PriceRangeItem> currentItems,
      LocalDate effectiveFrom) {
    if (currentRules == null || currentRules.isEmpty()) {
      return;
    }
    for (PriceRangeFactorRule rule : currentRules) {
      rule.setCurrentFlag(0);
      rule.setEffectiveTo(effectiveFrom);
      factorRuleMapper.updateById(rule);
    }
    if (currentItems == null) {
      return;
    }
    for (PriceRangeItem oldItem : currentItems) {
      oldItem.setCurrentFlag(0);
      itemMapper.updateById(oldItem);
    }
  }

  private record PendingFactorVersionExpiration(
      List<PriceRangeFactorRule> currentRules,
      List<PriceRangeItem> currentItems,
      LocalDate effectiveFrom) {}

  private record FactorItemBusinessSignature(
      String supplierIdentity,
      String supplierName,
      LocalDate effectiveFrom,
      LocalDate effectiveTo,
      BigDecimal rangeLow,
      BigDecimal rangeHigh,
      BigDecimal priceExclTax,
      BigDecimal priceInclTax,
      Integer taxIncluded) {}

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
