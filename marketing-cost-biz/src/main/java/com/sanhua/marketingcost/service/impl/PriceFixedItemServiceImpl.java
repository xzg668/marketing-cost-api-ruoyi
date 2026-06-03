package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceFixedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceFixedItemImportResponse;
import com.sanhua.marketingcost.dto.PriceFixedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceFixedItem;
import com.sanhua.marketingcost.mapper.PriceFixedItemMapper;
import com.sanhua.marketingcost.service.PriceFixedItemService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceFixedItemServiceImpl implements PriceFixedItemService {
  private static final int DEFAULT_TAX_INCLUDED = 1;
  private static final String SOURCE_TYPE_PURCHASE_FIXED = "PURCHASE_FIXED";
  private static final String SOURCE_TYPE_SETTLE_FIXED = "SETTLE_FIXED";
  private static final String SOURCE_SYSTEM_U9 = "U9";
  private static final String SOURCE_SYSTEM_EXCEL = "EXCEL";

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
    closePreviousVersions(item);
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
  public PriceFixedItemImportResponse importItems(PriceFixedItemImportRequest request) {
    PriceFixedItemImportResponse response = new PriceFixedItemImportResponse();
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return response;
    }
    for (var row : request.getRows()) {
      if (row == null) {
        response.incrementSkippedCount();
        response.addError(null, null, "空行，已跳过");
        continue;
      }
      String materialCode = trimToNull(row.getMaterialCode());
      if (materialCode == null) {
        response.incrementSkippedCount();
        response.addError(row.getSourceRowNo(), null, "物料代码为空，已跳过");
        continue;
      }
      normalizeSourceFields(row);
      if (isPurchaseFixed(row) && !isU9Source(row) && !StringUtils.hasText(row.getExternalRowId())) {
        response.incrementSkippedCount();
        response.addError(row.getSourceRowNo(), materialCode,
            "固定采购价非 U9 行缺少 externalRowId，已跳过");
        continue;
      }
      normalizeMixedSettleReference(row);
      if (row.getFixedPrice() == null && !StringUtils.hasText(row.getSettleReferenceText())) {
        response.incrementSkippedCount();
        response.addError(row.getSourceRowNo(), materialCode, "固定价为空，已跳过");
        continue;
      }
      if (row.getFixedPrice() == null && StringUtils.hasText(row.getSettleReferenceText())) {
        // 家用结算价9 最后一列是“价格或备注”混合列：备注行保留，但不参与取价。
        response.addWarning(row.getSourceRowNo(), materialCode,
            "结算固定价最后一列为备注，fixedPrice 为空，不参与取价");
      }
      PriceFixedItem item = findExisting(row);
      if (item == null) {
        item = new PriceFixedItem();
        fillItem(item, row, request);
        fillDefaults(item);
        closePreviousVersions(item);
        itemMapper.insert(item);
        response.incrementCreatedCount();
      } else {
        fillItem(item, row, request);
        fillDefaults(item);
        itemMapper.updateById(item);
        response.incrementUpdatedCount();
      }
      response.addItem(item);
    }
    return response;
  }

  /**
   * FPT-03 幂等规则：
   *
   * <p>1. 固定采购价非 U9：source_type + source_system + external_row_id。
   * 2. 固定采购价5 的 U9 行归结算固定价：source_type + source_system + material_code。
   * 3. 家用结算价9：source_type + source_system + material_code。
   *
   * <p>source_type 是业务强隔离边界，固定采购价和结算固定价不能只按料号互相覆盖。
   * 旧 PURCHASE/SETTLE 请求暂保留 V46 兼容查找，等前端全量切到新菜单后再清理。
   */
  private PriceFixedItem findExisting(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    if (isPurchaseFixed(row) && isU9Source(row)) {
      return itemMapper.selectOne(
          Wrappers.lambdaQuery(PriceFixedItem.class)
              .eq(PriceFixedItem::getSourceType, row.getSourceType())
              .eq(PriceFixedItem::getSourceSystem, row.getSourceSystem())
              .eq(PriceFixedItem::getMaterialCode, row.getMaterialCode().trim())
              .last("LIMIT 1"));
    }
    if (isPurchaseFixed(row) && !isU9Source(row)) {
      return itemMapper.selectOne(
          Wrappers.lambdaQuery(PriceFixedItem.class)
              .eq(PriceFixedItem::getSourceType, row.getSourceType())
              .eq(PriceFixedItem::getSourceSystem, row.getSourceSystem())
              .eq(PriceFixedItem::getExternalRowId, trimToNull(row.getExternalRowId()))
              .last("LIMIT 1"));
    }
    if (isSettleFixed(row) && (isU9Source(row) || SOURCE_SYSTEM_EXCEL.equals(row.getSourceSystem()))) {
      return itemMapper.selectOne(
          Wrappers.lambdaQuery(PriceFixedItem.class)
              .eq(PriceFixedItem::getSourceType, row.getSourceType())
              .eq(PriceFixedItem::getSourceSystem, row.getSourceSystem())
              .eq(PriceFixedItem::getMaterialCode, row.getMaterialCode().trim())
              .last("LIMIT 1"));
    }

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
      PriceFixedItemImportRequest.PriceFixedItemImportRow row,
      PriceFixedItemImportRequest request) {
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
    // FPT-02：来源追溯字段，后续导入报错可直接定位到文件、sheet 和 Excel 行号。
    if (row.getSourceSystem() != null) item.setSourceSystem(row.getSourceSystem());
    if (row.getSourceSheetName() != null) item.setSourceSheetName(row.getSourceSheetName());
    if (row.getSourceRowNo() != null) item.setSourceRowNo(row.getSourceRowNo());
    item.setSourceBatchNo(firstText(row.getSourceBatchNo(), request.getSourceBatchNo()));
    item.setImportFileName(firstText(row.getImportFileName(), request.getImportFileName()));
    item.setImportedBy(firstText(row.getImportedBy(), request.getImportedBy()));
    item.setImportedAt(row.getImportedAt() != null ? row.getImportedAt() : LocalDateTime.now());

    if (row.getExternalRowId() != null) item.setExternalRowId(row.getExternalRowId());
    if (row.getProcessStatus() != null) item.setProcessStatus(row.getProcessStatus());
    if (row.getSrmDocNo() != null) item.setSrmDocNo(row.getSrmDocNo());
    if (row.getMaterialCategory() != null) item.setMaterialCategory(row.getMaterialCategory());
    if (row.getTaxRate() != null) item.setTaxRate(row.getTaxRate());
    if (row.getOriginalProcessFee() != null) item.setOriginalProcessFee(row.getOriginalProcessFee());
    if (row.getOriginalProcessFeeTaxIncluded() != null) {
      item.setOriginalProcessFeeTaxIncluded(row.getOriginalProcessFeeTaxIncluded());
    }
    if (row.getOriginalTaxExcludedPrice() != null) item.setOriginalTaxExcludedPrice(row.getOriginalTaxExcludedPrice());
    if (row.getOriginalTaxIncludedPrice() != null) item.setOriginalTaxIncludedPrice(row.getOriginalTaxIncludedPrice());
    if (row.getOriginalSupplierName() != null) item.setOriginalSupplierName(row.getOriginalSupplierName());
    if (row.getCurrentProcessFee() != null) item.setCurrentProcessFee(row.getCurrentProcessFee());
    if (row.getCurrentProcessFeeTaxIncluded() != null) {
      item.setCurrentProcessFeeTaxIncluded(row.getCurrentProcessFeeTaxIncluded());
    }
    if (row.getCurrentTaxExcludedPrice() != null) item.setCurrentTaxExcludedPrice(row.getCurrentTaxExcludedPrice());
    if (row.getCurrentTaxIncludedPrice() != null) item.setCurrentTaxIncludedPrice(row.getCurrentTaxIncludedPrice());
    if (row.getCurrentSupplierName() != null) item.setCurrentSupplierName(row.getCurrentSupplierName());
    if (row.getChangeAmount() != null) item.setChangeAmount(row.getChangeAmount());
    if (row.getChangeRate() != null) item.setChangeRate(row.getChangeRate());
    if (row.getExecutionPeriodText() != null) item.setExecutionPeriodText(row.getExecutionPeriodText());
    if (row.getAnnualUsageText() != null) item.setAnnualUsageText(row.getAnnualUsageText());
    if (row.getApplicant() != null) item.setApplicant(row.getApplicant());
    if (row.getApplyDept() != null) item.setApplyDept(row.getApplyDept());
    if (row.getMarketSituation() != null) item.setMarketSituation(row.getMarketSituation());
    if (row.getSimilarCompare() != null) item.setSimilarCompare(row.getSimilarCompare());
    if (row.getApprovalConclusion() != null) item.setApprovalConclusion(row.getApprovalConclusion());
    if (row.getApprovalType() != null) item.setApprovalType(row.getApprovalType());
    if (row.getBusinessDivision() != null) item.setBusinessDivision(row.getBusinessDivision());
    if (row.getGeneralManagerApprovedAt() != null) {
      item.setGeneralManagerApprovedAt(row.getGeneralManagerApprovedAt());
    }
    if (row.getTrackingDate() != null) item.setTrackingDate(row.getTrackingDate());
    if (row.getPrintFlag() != null) item.setPrintFlag(row.getPrintFlag());
    if (row.getSettleReferenceHeader() != null) item.setSettleReferenceHeader(row.getSettleReferenceHeader());
    if (row.getSettleReferenceText() != null) item.setSettleReferenceText(row.getSettleReferenceText());
    if (row.getSettleReferencePrice() != null) item.setSettleReferencePrice(row.getSettleReferencePrice());
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
    if (item.getEffectiveFrom() == null) {
      item.setEffectiveFrom(defaultEffectiveFrom(item.getPricingMonth()));
    }
  }

  private void closePreviousVersions(PriceFixedItem item) {
    if (item == null || item.getEffectiveFrom() == null || !StringUtils.hasText(item.getMaterialCode())) {
      return;
    }
    var query = Wrappers.lambdaQuery(PriceFixedItem.class)
        .eq(PriceFixedItem::getMaterialCode, item.getMaterialCode())
        .eq(PriceFixedItem::getSourceType, item.getSourceType())
        .and(q -> q.isNull(PriceFixedItem::getEffectiveTo)
            .or()
            .gt(PriceFixedItem::getEffectiveTo, item.getEffectiveFrom()));
    String supplierCode = trimToNull(item.getSupplierCode());
    if (supplierCode == null) {
      query.isNull(PriceFixedItem::getSupplierCode);
    } else {
      query.eq(PriceFixedItem::getSupplierCode, supplierCode);
    }
    String businessUnitType = trimToNull(item.getBusinessUnitType());
    if (businessUnitType == null) {
      query.and(q -> q.isNull(PriceFixedItem::getBusinessUnitType)
          .or()
          .eq(PriceFixedItem::getBusinessUnitType, ""));
    } else {
      query.eq(PriceFixedItem::getBusinessUnitType, businessUnitType);
    }
    String sourceSystem = trimToNull(item.getSourceSystem());
    if (sourceSystem == null) {
      query.isNull(PriceFixedItem::getSourceSystem);
    } else {
      query.eq(PriceFixedItem::getSourceSystem, sourceSystem);
    }
    for (PriceFixedItem row : itemMapper.selectList(query)) {
      if (item.getId() != null && item.getId().equals(row.getId())) {
        continue;
      }
      row.setEffectiveTo(item.getEffectiveFrom());
      itemMapper.updateById(row);
    }
  }

  private LocalDate defaultEffectiveFrom(String pricingMonth) {
    if (StringUtils.hasText(pricingMonth)) {
      try {
        return YearMonth.parse(pricingMonth.trim()).atDay(1);
      } catch (java.time.format.DateTimeParseException ignored) {
        // fall through
      }
    }
    return LocalDate.now();
  }

  private String trimToNull(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    return value.trim();
  }

  private String firstText(String first, String second) {
    String normalizedFirst = trimToNull(first);
    if (normalizedFirst != null) {
      return normalizedFirst;
    }
    return trimToNull(second);
  }

  private void normalizeMixedSettleReference(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    if (row.getFixedPrice() == null && row.getSettleReferencePrice() != null) {
      row.setFixedPrice(row.getSettleReferencePrice());
    }
    if (row.getSettleReferencePrice() == null && row.getFixedPrice() != null) {
      row.setSettleReferencePrice(row.getFixedPrice());
    }
  }

  private void normalizeSourceFields(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    String sourceType = upperTrim(row.getSourceType());
    if (sourceType != null) {
      row.setSourceType(sourceType);
    }
    String sourceSystem = upperTrim(row.getSourceSystem());
    if (sourceSystem == null && isU9ProcessNo(row.getProcessNo())) {
      sourceSystem = SOURCE_SYSTEM_U9;
    }
    if (sourceSystem == null && (SOURCE_TYPE_PURCHASE_FIXED.equals(sourceType)
        || SOURCE_TYPE_SETTLE_FIXED.equals(sourceType))) {
      sourceSystem = SOURCE_SYSTEM_EXCEL;
    }
    if (sourceSystem != null) {
      row.setSourceSystem(sourceSystem);
    }
    if (StringUtils.hasText(row.getExternalRowId())) {
      row.setExternalRowId(row.getExternalRowId().trim());
    }
  }

  private boolean isPurchaseFixed(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    return SOURCE_TYPE_PURCHASE_FIXED.equals(row.getSourceType());
  }

  private boolean isSettleFixed(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    return SOURCE_TYPE_SETTLE_FIXED.equals(row.getSourceType());
  }

  private boolean isU9Source(PriceFixedItemImportRequest.PriceFixedItemImportRow row) {
    return SOURCE_SYSTEM_U9.equals(row.getSourceSystem());
  }

  private boolean isU9ProcessNo(String processNo) {
    String normalized = upperTrim(processNo);
    return normalized != null && normalized.startsWith("U9");
  }

  private String upperTrim(String value) {
    String trimmed = trimToNull(value);
    return trimmed == null ? null : trimmed.toUpperCase();
  }
}
