package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.dto.FinanceBasePriceImportRequest;
import com.sanhua.marketingcost.dto.FinanceBasePriceRequest;
import com.sanhua.marketingcost.entity.FinanceBasePrice;
import com.sanhua.marketingcost.mapper.FinanceBasePriceMapper;
import com.sanhua.marketingcost.service.FinanceBasePriceService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinanceBasePriceServiceImpl implements FinanceBasePriceService {
  private final FinanceBasePriceMapper financeBasePriceMapper;

  public FinanceBasePriceServiceImpl(FinanceBasePriceMapper financeBasePriceMapper) {
    this.financeBasePriceMapper = financeBasePriceMapper;
  }

  @Override
  public List<FinanceBasePrice> list(String priceMonth, String keyword) {
    var query = Wrappers.lambdaQuery(FinanceBasePrice.class);
    String resolvedMonth = resolvePriceMonth(priceMonth);
    if (StringUtils.hasText(resolvedMonth)) {
      query.eq(FinanceBasePrice::getPriceMonth, resolvedMonth);
    }
    if (StringUtils.hasText(keyword)) {
      String like = keyword.trim();
      query.and(wrapper -> wrapper.like(FinanceBasePrice::getFactorName, like)
          .or()
          .like(FinanceBasePrice::getShortName, like));
    }
    query.orderByAsc(FinanceBasePrice::getSeq).orderByAsc(FinanceBasePrice::getId);
    return financeBasePriceMapper.selectList(query);
  }

  @Override
  public FinanceBasePrice create(FinanceBasePriceRequest request) {
    FinanceBasePrice entity = toEntity(request);
    fillDefaults(entity);
    financeBasePriceMapper.insert(entity);
    return entity;
  }

  @Override
  public FinanceBasePrice update(Long id, FinanceBasePriceRequest request) {
    FinanceBasePrice existing = financeBasePriceMapper.selectById(id);
    if (existing == null) {
      return null;
    }
    merge(existing, request);
    fillDefaults(existing);
    financeBasePriceMapper.updateById(existing);
    return existing;
  }

  @Override
  public boolean delete(Long id) {
    return financeBasePriceMapper.deleteById(id) > 0;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public List<FinanceBasePrice> importPrices(FinanceBasePriceImportRequest request) {
    if (request == null || !StringUtils.hasText(request.getPriceMonth())
        || request.getRows() == null || request.getRows().isEmpty()) {
      return List.of();
    }
    String priceMonth = request.getPriceMonth().trim();
    List<FinanceBasePrice> imported = new ArrayList<>();
    for (var row : request.getRows()) {
      FinanceBasePrice existing = findExisting(priceMonth, row);
      FinanceBasePrice entity = existing != null ? existing : new FinanceBasePrice();
      entity.setPriceMonth(priceMonth);
      entity.setSeq(row.getSeq());
      entity.setFactorName(row.getFactorName());
      entity.setShortName(row.getShortName());
      entity.setFactorCode(resolveFactorCode(row.getFactorCode(), row.getShortName(),
          row.getFactorName()));
      entity.setPriceSource(row.getPriceSource());
      entity.setPrice(row.getPrice());
      entity.setUnit(row.getUnit());
      entity.setLinkType(row.getLinkType());
      fillDefaults(entity);
      if (existing == null) {
        financeBasePriceMapper.insert(entity);
      } else {
        financeBasePriceMapper.updateById(entity);
      }
      imported.add(entity);
    }
    return imported;
  }

  private FinanceBasePrice findExisting(String priceMonth,
      FinanceBasePriceImportRequest.FinanceBasePriceImportRow row) {
    if (row == null) {
      return null;
    }
    if (row.getSeq() != null) {
      return financeBasePriceMapper.selectOne(Wrappers.lambdaQuery(FinanceBasePrice.class)
          .eq(FinanceBasePrice::getPriceMonth, priceMonth)
          .eq(FinanceBasePrice::getSeq, row.getSeq()));
    }
    if (StringUtils.hasText(row.getShortName()) && StringUtils.hasText(row.getFactorName())) {
      return financeBasePriceMapper.selectOne(Wrappers.lambdaQuery(FinanceBasePrice.class)
          .eq(FinanceBasePrice::getPriceMonth, priceMonth)
          .eq(FinanceBasePrice::getShortName, row.getShortName())
          .eq(FinanceBasePrice::getFactorName, row.getFactorName()));
    }
    if (StringUtils.hasText(row.getShortName())) {
      return financeBasePriceMapper.selectOne(Wrappers.lambdaQuery(FinanceBasePrice.class)
          .eq(FinanceBasePrice::getPriceMonth, priceMonth)
          .eq(FinanceBasePrice::getShortName, row.getShortName()));
    }
    if (StringUtils.hasText(row.getFactorName())) {
      return financeBasePriceMapper.selectOne(Wrappers.lambdaQuery(FinanceBasePrice.class)
          .eq(FinanceBasePrice::getPriceMonth, priceMonth)
          .eq(FinanceBasePrice::getFactorName, row.getFactorName()));
    }
    return null;
  }

  private FinanceBasePrice toEntity(FinanceBasePriceRequest request) {
    FinanceBasePrice entity = new FinanceBasePrice();
    merge(entity, request);
    return entity;
  }

  private void merge(FinanceBasePrice entity, FinanceBasePriceRequest request) {
    if (request == null) {
      return;
    }
    if (StringUtils.hasText(request.getPriceMonth())) {
      entity.setPriceMonth(request.getPriceMonth().trim());
    }
    if (request.getSeq() != null) {
      entity.setSeq(request.getSeq());
    }
    if (request.getFactorName() != null) {
      entity.setFactorName(request.getFactorName());
    }
    if (request.getShortName() != null) {
      entity.setShortName(request.getShortName());
    }
    if (request.getPriceSource() != null) {
      entity.setPriceSource(request.getPriceSource());
    }
    if (request.getPrice() != null) {
      entity.setPrice(request.getPrice());
    }
    if (request.getUnit() != null) {
      entity.setUnit(request.getUnit());
    }
    if (request.getLinkType() != null) {
      entity.setLinkType(request.getLinkType());
    }
    String factorCode = resolveFactorCode(request.getFactorCode(), request.getShortName(),
        request.getFactorName());
    if (StringUtils.hasText(factorCode)) {
      entity.setFactorCode(factorCode);
    }
  }

  private void fillDefaults(FinanceBasePrice entity) {
    if (!StringUtils.hasText(entity.getUnit())) {
      entity.setUnit("公斤");
    }
    if (!StringUtils.hasText(entity.getLinkType())) {
      entity.setLinkType("固定");
    }
    String resolved = resolveFactorCode(entity.getFactorCode(), entity.getShortName(),
        entity.getFactorName());
    if (StringUtils.hasText(resolved)) {
      entity.setFactorCode(resolved);
    }
  }

  private String resolveFactorCode(String factorCode, String shortName, String factorName) {
    if (StringUtils.hasText(factorCode)) {
      return factorCode.trim();
    }
    String text = (shortName == null ? "" : shortName)
        + " "
        + (factorName == null ? "" : factorName);
    String upper = text.toUpperCase();
    if (upper.contains("CU")) {
      return "Cu";
    }
    if (upper.contains("ZN")) {
      return "Zn";
    }
    if (upper.contains("AL")) {
      return "Al";
    }
    if (upper.contains("SN")) {
      return "Sn";
    }
    if (upper.contains("CN")) {
      return "Cn";
    }
    return null;
  }

  private String resolvePriceMonth(String priceMonth) {
    if (StringUtils.hasText(priceMonth)) {
      return priceMonth.trim();
    }
    FinanceBasePrice latest = financeBasePriceMapper.selectOne(
        Wrappers.lambdaQuery(FinanceBasePrice.class)
            .select(FinanceBasePrice::getPriceMonth)
            .orderByDesc(FinanceBasePrice::getPriceMonth)
            .last("LIMIT 1"));
    if (latest == null) {
      return null;
    }
    return latest.getPriceMonth();
  }
}
