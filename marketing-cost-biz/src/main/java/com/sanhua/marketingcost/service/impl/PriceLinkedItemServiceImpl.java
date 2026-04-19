package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.PriceLinkedItemDto;
import com.sanhua.marketingcost.dto.PriceLinkedItemImportRequest;
import com.sanhua.marketingcost.dto.PriceLinkedItemUpdateRequest;
import com.sanhua.marketingcost.entity.PriceLinkedItem;
import com.sanhua.marketingcost.mapper.PriceLinkedItemMapper;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceLinkedItemServiceImpl implements PriceLinkedItemService {
  private final PriceLinkedItemMapper itemMapper;

  public PriceLinkedItemServiceImpl(PriceLinkedItemMapper itemMapper) {
    this.itemMapper = itemMapper;
  }

  @Override
  public List<PriceLinkedItemDto> list(String pricingMonth, String materialCode) {
    String resolvedMonth = resolvePricingMonth(pricingMonth);
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class);
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(PriceLinkedItem::getPricingMonth, resolvedMonth);
    }
    if (StringUtils.hasText(materialCode)) {
      query.like(PriceLinkedItem::getMaterialCode, materialCode.trim());
    }
    query.orderByAsc(PriceLinkedItem::getId);
    return itemMapper.selectList(query).stream()
        .map(this::toDto)
        .toList();
  }

  @Override
  public PriceLinkedItemDto create(PriceLinkedItemUpdateRequest request) {
    if (request == null) {
      return null;
    }
    PriceLinkedItem item = new PriceLinkedItem();
    merge(item, request);
    if (!StringUtils.hasText(item.getPricingMonth())) {
      item.setPricingMonth(resolvePricingMonth(null));
    }
    if (!StringUtils.hasText(item.getPricingMonth())) {
      return null;
    }
    itemMapper.insert(item);
    return toDto(item);
  }

  @Override
  public PriceLinkedItemDto update(Long id, PriceLinkedItemUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceLinkedItem item = itemMapper.selectById(id);
    if (item == null) {
      return null;
    }
    merge(item, request);
    itemMapper.updateById(item);
    return toDto(item);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceLinkedItemDto> importItems(PriceLinkedItemImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    String fallbackMonth = StringUtils.hasText(request.getPricingMonth())
        ? request.getPricingMonth().trim()
        : null;
    List<PriceLinkedItemDto> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !StringUtils.hasText(row.getMaterialCode())) {
        continue;
      }
      String pricingMonth = StringUtils.hasText(row.getPricingMonth())
          ? row.getPricingMonth().trim()
          : fallbackMonth;
      if (!StringUtils.hasText(pricingMonth)) {
        continue;
      }
      PriceLinkedItem item = findExisting(pricingMonth, row);
      if (item == null) {
        item = new PriceLinkedItem();
        item.setPricingMonth(pricingMonth);
        fillItem(item, pricingMonth, row);
        itemMapper.insert(item);
      } else {
        fillItem(item, pricingMonth, row);
        itemMapper.updateById(item);
      }
      imported.add(toDto(item));
    }
    return imported;
  }

  @Override
  public boolean delete(Long id) {
    if (id == null) {
      return false;
    }
    return itemMapper.deleteById(id) > 0;
  }

  private String resolvePricingMonth(String pricingMonth) {
    if (StringUtils.hasText(pricingMonth)) {
      return pricingMonth.trim();
    }
    PriceLinkedItem latest = itemMapper.selectOne(Wrappers.lambdaQuery(PriceLinkedItem.class)
        .select(PriceLinkedItem::getPricingMonth)
        .orderByDesc(PriceLinkedItem::getPricingMonth)
        .last("LIMIT 1"));
    return latest == null ? null : latest.getPricingMonth();
  }

  private PriceLinkedItem findExisting(String pricingMonth,
      PriceLinkedItemImportRequest.PriceLinkedItemImportRow row) {
    var query = Wrappers.lambdaQuery(PriceLinkedItem.class)
        .eq(PriceLinkedItem::getPricingMonth, pricingMonth)
        .eq(PriceLinkedItem::getMaterialCode, row.getMaterialCode());
    if (StringUtils.hasText(row.getSupplierCode())) {
      query.eq(PriceLinkedItem::getSupplierCode, row.getSupplierCode());
    }
    if (StringUtils.hasText(row.getSpecModel())) {
      query.eq(PriceLinkedItem::getSpecModel, row.getSpecModel());
    }
    return itemMapper.selectOne(query.last("LIMIT 1"));
  }

  private void fillItem(PriceLinkedItem item, String pricingMonth,
      PriceLinkedItemImportRequest.PriceLinkedItemImportRow row) {
    item.setPricingMonth(pricingMonth);
    item.setOrgCode(row.getOrgCode());
    item.setSourceName(row.getSourceName());
    item.setSupplierName(row.getSupplierName());
    item.setSupplierCode(row.getSupplierCode());
    item.setPurchaseClass(row.getPurchaseClass());
    item.setMaterialName(row.getMaterialName());
    item.setMaterialCode(row.getMaterialCode());
    item.setSpecModel(row.getSpecModel());
    item.setUnit(row.getUnit());
    item.setFormulaExpr(row.getFormulaExpr());
    item.setFormulaExprCn(row.getFormulaExprCn());
    item.setBlankWeight(row.getBlankWeight());
    item.setNetWeight(row.getNetWeight());
    item.setProcessFee(row.getProcessFee());
    item.setAgentFee(row.getAgentFee());
    item.setManualPrice(row.getManualPrice());
    if (row.getTaxIncluded() != null) {
      item.setTaxIncluded(row.getTaxIncluded() ? 1 : 0);
    }
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setOrderType(row.getOrderType());
    item.setQuota(row.getQuota());
  }

  private void merge(PriceLinkedItem item, PriceLinkedItemUpdateRequest request) {
    if (request == null) {
      return;
    }
    if (StringUtils.hasText(request.getPricingMonth())) {
      item.setPricingMonth(request.getPricingMonth().trim());
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
    if (request.getFormulaExprCn() != null) {
      item.setFormulaExprCn(request.getFormulaExprCn());
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
    if (request.getManualPrice() != null) {
      item.setManualPrice(request.getManualPrice());
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
  }

  private PriceLinkedItemDto toDto(PriceLinkedItem item) {
    PriceLinkedItemDto dto = new PriceLinkedItemDto();
    dto.setId(item.getId());
    dto.setPricingMonth(item.getPricingMonth());
    dto.setOrgCode(item.getOrgCode());
    dto.setSourceName(item.getSourceName());
    dto.setSupplierName(item.getSupplierName());
    dto.setSupplierCode(item.getSupplierCode());
    dto.setPurchaseClass(item.getPurchaseClass());
    dto.setMaterialName(item.getMaterialName());
    dto.setMaterialCode(item.getMaterialCode());
    dto.setSpecModel(item.getSpecModel());
    dto.setUnit(item.getUnit());
    dto.setFormulaExpr(item.getFormulaExpr());
    dto.setFormulaExprCn(item.getFormulaExprCn());
    dto.setBlankWeight(item.getBlankWeight());
    dto.setNetWeight(item.getNetWeight());
    dto.setProcessFee(item.getProcessFee());
    dto.setAgentFee(item.getAgentFee());
    dto.setManualPrice(item.getManualPrice());
    dto.setTaxIncluded(item.getTaxIncluded());
    dto.setEffectiveFrom(item.getEffectiveFrom());
    dto.setEffectiveTo(item.getEffectiveTo());
    dto.setOrderType(item.getOrderType());
    dto.setQuota(item.getQuota());
    dto.setUpdatedAt(item.getUpdatedAt());
    return dto;
  }
}
