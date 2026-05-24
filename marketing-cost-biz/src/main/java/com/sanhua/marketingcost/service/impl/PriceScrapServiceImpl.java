package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.mapper.PriceScrapMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.PriceScrapService;
import com.sanhua.marketingcost.util.CmsFieldNormalizeUtils;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 废料回收价内部查询 service 实现；旧页面维护接口已在 MPPG-08 删除。 */
@Service
public class PriceScrapServiceImpl implements PriceScrapService {

  private final PriceScrapMapper scrapMapper;

  public PriceScrapServiceImpl(PriceScrapMapper scrapMapper) {
    this.scrapMapper = scrapMapper;
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
