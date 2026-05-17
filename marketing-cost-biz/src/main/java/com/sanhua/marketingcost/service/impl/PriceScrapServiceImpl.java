package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceScrapService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 废料回收价 service 实现 (V48) */
@Service
public class PriceScrapServiceImpl implements PriceScrapService {

  private static final int DEFAULT_TAX_INCLUDED = 1;
  private static final String DEFAULT_PRICING_MONTH = "CURRENT";

  private final PriceScrapMapper scrapMapper;

  public PriceScrapServiceImpl(PriceScrapMapper scrapMapper) {
    this.scrapMapper = scrapMapper;
  }

  @Override
  public Page<PriceScrap> page(String scrapCode, String pricingMonth, int page, int pageSize) {
    var query = Wrappers.lambdaQuery(PriceScrap.class);
    if (StringUtils.hasText(scrapCode)) {
      query.like(PriceScrap::getScrapCode, scrapCode.trim());
    }
    if (StringUtils.hasText(pricingMonth)) {
      query.eq(PriceScrap::getPricingMonth, pricingMonth.trim());
    }
    query.orderByDesc(PriceScrap::getId);
    Page<PriceScrap> pager = new Page<>(page, pageSize);
    return scrapMapper.selectPage(pager, query);
  }

  @Override
  public PriceScrap getCurrentByScrapCode(String scrapCode) {
    if (!isValidCmsScrapCode(scrapCode)) {
      return null;
    }
    return findCurrentByScrapCode(scrapCode);
  }

  @Override
  public Map<String, PriceScrap> getCurrentByScrapCodes(Collection<String> scrapCodes) {
    if (scrapCodes == null || scrapCodes.isEmpty()) {
      return Map.of();
    }
    LinkedHashSet<String> normalizedCodes = new LinkedHashSet<>();
    for (String scrapCode : scrapCodes) {
      if (isValidCmsScrapCode(scrapCode)) {
        normalizedCodes.add(normalizeScrapCode(scrapCode));
      }
    }
    if (normalizedCodes.isEmpty()) {
      return Map.of();
    }
    var query = Wrappers.lambdaQuery(PriceScrap.class)
        .in(PriceScrap::getScrapCode, normalizedCodes)
        .eq(PriceScrap::getDeleted, 0)
        .orderByDesc(PriceScrap::getId);
    String businessUnitType = currentBusinessUnitType();
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(PriceScrap::getBusinessUnitType, businessUnitType);
    }
    List<PriceScrap> rows = scrapMapper.selectList(query);
    if (rows == null || rows.isEmpty()) {
      return Map.of();
    }
    Map<String, PriceScrap> currentByCode = new LinkedHashMap<>();
    for (PriceScrap row : rows) {
      String normalizedCode = normalizeScrapCode(row.getScrapCode());
      if (StringUtils.hasText(normalizedCode)) {
        currentByCode.putIfAbsent(normalizedCode, row);
      }
    }
    return currentByCode;
  }

  @Override
  public PriceScrap create(PriceScrapUpdateRequest request) {
    if (request == null || !isValidCmsScrapCode(request.getScrapCode())) {
      return null;
    }
    PriceScrap item = findCurrentByScrapCode(request.getScrapCode());
    boolean insert = item == null;
    if (insert) {
      item = new PriceScrap();
    }
    merge(item, request);
    fillDefaults(item);
    if (insert) {
      scrapMapper.insert(item);
    } else {
      scrapMapper.updateById(item);
    }
    return item;
  }

  @Override
  public PriceScrap update(Long id, PriceScrapUpdateRequest request) {
    if (id == null) {
      return null;
    }
    PriceScrap existing = scrapMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    scrapMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return id != null && scrapMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<PriceScrap> importItems(PriceScrapImportRequest request) {
    if (request == null || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    List<PriceScrap> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      if (row == null || !isValidCmsScrapCode(row.getScrapCode())) {
        continue;
      }
      PriceScrap existing = findCurrentByScrapCode(row.getScrapCode());
      if (existing == null) {
        PriceScrap item = new PriceScrap();
        fillFromRow(item, row);
        fillDefaults(item);
        scrapMapper.insert(item);
        imported.add(item);
      } else {
        fillFromRow(existing, row);
        fillDefaults(existing);
        scrapMapper.updateById(existing);
        imported.add(existing);
      }
    }
    return imported;
  }

  /** 当前废料价去重锚点是 CMS 回收料号；pricingMonth 不参与取价和去重。 */
  private PriceScrap findCurrentByScrapCode(String scrapCode) {
    var query = Wrappers.lambdaQuery(PriceScrap.class)
        .eq(PriceScrap::getScrapCode, normalizeScrapCode(scrapCode))
        .eq(PriceScrap::getDeleted, 0)
        .orderByDesc(PriceScrap::getId);
    String businessUnitType = currentBusinessUnitType();
    if (StringUtils.hasText(businessUnitType)) {
      query.eq(PriceScrap::getBusinessUnitType, businessUnitType);
    }
    List<PriceScrap> rows = scrapMapper.selectList(query);
    return rows == null || rows.isEmpty() ? null : rows.get(0);
  }

  private void fillFromRow(PriceScrap item, PriceScrapImportRequest.PriceScrapImportRow row) {
    item.setPricingMonth(row.getPricingMonth());
    item.setScrapCode(row.getScrapCode());
    item.setScrapName(row.getScrapName());
    item.setSpecModel(row.getSpecModel());
    item.setUnit(row.getUnit());
    item.setRecyclePrice(row.getRecyclePrice());
    if (row.getTaxIncluded() != null) {
      item.setTaxIncluded(row.getTaxIncluded() ? 1 : 0);
    }
    item.setEffectiveFrom(row.getEffectiveFrom());
    item.setEffectiveTo(row.getEffectiveTo());
    item.setRemark(row.getRemark());
  }

  private void merge(PriceScrap item, PriceScrapUpdateRequest request) {
    if (request == null) return;
    if (request.getPricingMonth() != null) item.setPricingMonth(request.getPricingMonth());
    if (request.getScrapCode() != null) item.setScrapCode(request.getScrapCode());
    if (request.getScrapName() != null) item.setScrapName(request.getScrapName());
    if (request.getSpecModel() != null) item.setSpecModel(request.getSpecModel());
    if (request.getUnit() != null) item.setUnit(request.getUnit());
    if (request.getRecyclePrice() != null) item.setRecyclePrice(request.getRecyclePrice());
    if (request.getTaxIncluded() != null) item.setTaxIncluded(request.getTaxIncluded() ? 1 : 0);
    if (request.getEffectiveFrom() != null) item.setEffectiveFrom(request.getEffectiveFrom());
    if (request.getEffectiveTo() != null) item.setEffectiveTo(request.getEffectiveTo());
    if (request.getRemark() != null) item.setRemark(request.getRemark());
  }

  private void fillDefaults(PriceScrap item) {
    if (item.getTaxIncluded() == null) item.setTaxIncluded(DEFAULT_TAX_INCLUDED);
    if (StringUtils.hasText(item.getScrapCode())) item.setScrapCode(normalizeScrapCode(item.getScrapCode()));
    if (!StringUtils.hasText(item.getPricingMonth())) item.setPricingMonth(DEFAULT_PRICING_MONTH);
  }

  private boolean isValidCmsScrapCode(String scrapCode) {
    String normalized = normalizeScrapCode(scrapCode);
    if (!StringUtils.hasText(normalized)) {
      return false;
    }
    // 新废料价必须使用 CMS 回收料号；A/B/C/D 历史等级不再进入新取价链路。
    return !normalized.matches("[A-D]");
  }

  private String normalizeScrapCode(String scrapCode) {
    return CmsFieldNormalizeUtils.normalize(scrapCode);
  }

  private String currentBusinessUnitType() {
    String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
    return StringUtils.hasText(businessUnitType) ? businessUnitType.trim() : null;
  }
}
