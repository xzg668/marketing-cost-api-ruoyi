package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.PriceScrapImportRequest;
import com.sanhua.marketingcost.dto.PriceScrapUpdateRequest;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.service.PriceScrapService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 废料回收价 service 实现 (V48) */
@Service
public class PriceScrapServiceImpl implements PriceScrapService {

  private static final int DEFAULT_TAX_INCLUDED = 1;
  private static final String DEFAULT_PRICING_MONTH = "2026-03";

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
  public PriceScrap create(PriceScrapUpdateRequest request) {
    if (request == null || !StringUtils.hasText(request.getScrapCode())) {
      return null;
    }
    PriceScrap item = new PriceScrap();
    merge(item, request);
    fillDefaults(item);
    scrapMapper.insert(item);
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
      if (row == null || !StringUtils.hasText(row.getScrapCode())) {
        continue;
      }
      PriceScrap existing = findExisting(row);
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

  /** 去重锚点：(scrap_code, pricing_month) —— BU 由全局拦截器自动注入 */
  private PriceScrap findExisting(PriceScrapImportRequest.PriceScrapImportRow row) {
    String pricingMonth = StringUtils.hasText(row.getPricingMonth())
        ? row.getPricingMonth().trim()
        : DEFAULT_PRICING_MONTH;
    var query = Wrappers.lambdaQuery(PriceScrap.class)
        .eq(PriceScrap::getScrapCode, row.getScrapCode().trim())
        .eq(PriceScrap::getPricingMonth, pricingMonth)
        .last("LIMIT 1");
    return scrapMapper.selectOne(query);
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
    if (StringUtils.hasText(item.getScrapCode())) item.setScrapCode(item.getScrapCode().trim());
    if (!StringUtils.hasText(item.getPricingMonth())) item.setPricingMonth(DEFAULT_PRICING_MONTH);
  }
}
