package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.sanhua.marketingcost.entity.CostRunResult;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.mapper.CostRunResultMapper;
import com.sanhua.marketingcost.mapper.QuoteCostRunVersionMapper;
import com.sanhua.marketingcost.service.QuoteCostRunVersionInvalidationService;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class QuoteCostRunVersionInvalidationServiceImpl
    implements QuoteCostRunVersionInvalidationService {

  static final String STATUS_TRIAL = "TRIAL";
  static final String STATUS_CONFIRMED = "CONFIRMED";
  static final String STATUS_STALE = "STALE";
  private static final int CHUNK_SIZE = 500;

  private final QuoteCostRunVersionMapper versionMapper;
  private final CostRunResultMapper resultMapper;

  public QuoteCostRunVersionInvalidationServiceImpl(
      QuoteCostRunVersionMapper versionMapper, CostRunResultMapper resultMapper) {
    this.versionMapper = versionMapper;
    this.resultMapper = resultMapper;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int invalidateByFinanceCu(String pricingMonth, String businessUnitType) {
    String month = required("pricingMonth", pricingMonth);
    String businessUnit = required("businessUnitType", businessUnitType);
    return invalidate(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .eq(QuoteCostRunVersion::getPricingMonth, month)
            .eq(QuoteCostRunVersion::getBusinessUnitType, businessUnit),
        List.of(STATUS_TRIAL));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int invalidateByOaCu(String oaNo) {
    return invalidate(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .eq(QuoteCostRunVersion::getOaNo, required("oaNo", oaNo)),
        List.of(STATUS_TRIAL));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int invalidateProduct(
      String oaNo, Long oaFormItemId, String productCode, String pricingMonth) {
    if (oaFormItemId == null) {
      throw new IllegalArgumentException("oaFormItemId 不能为空");
    }
    return invalidate(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .eq(QuoteCostRunVersion::getOaNo, required("oaNo", oaNo))
            .eq(QuoteCostRunVersion::getOaFormItemId, oaFormItemId)
            .eq(QuoteCostRunVersion::getProductCode, required("productCode", productCode))
            .eq(QuoteCostRunVersion::getPricingMonth, required("pricingMonth", pricingMonth)),
        List.of(STATUS_TRIAL));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int invalidateProductAfterBomChange(
      String oaNo,
      Long oaFormItemId,
      String productCode,
      String pricingMonth) {
    if (oaFormItemId == null) {
      throw new IllegalArgumentException("oaFormItemId 不能为空");
    }
    return invalidate(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .eq(QuoteCostRunVersion::getOaNo, required("oaNo", oaNo))
            .eq(QuoteCostRunVersion::getOaFormItemId, oaFormItemId)
            .eq(
                QuoteCostRunVersion::getProductCode,
                required("productCode", productCode))
            .eq(
                QuoteCostRunVersion::getPricingMonth,
                required("pricingMonth", pricingMonth)),
        List.of(STATUS_TRIAL, STATUS_CONFIRMED));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int invalidateByPriceTypeConfirmNos(Collection<String> confirmNos) {
    LinkedHashSet<String> normalized = new LinkedHashSet<>();
    if (confirmNos != null) {
      for (String confirmNo : confirmNos) {
        if (StringUtils.hasText(confirmNo)) {
          normalized.add(confirmNo.trim());
        }
      }
    }
    if (normalized.isEmpty()) {
      return 0;
    }
    return invalidate(
        Wrappers.<QuoteCostRunVersion>lambdaQuery()
            .in(QuoteCostRunVersion::getPriceTypeConfirmNo, normalized),
        List.of(STATUS_TRIAL));
  }

  private int invalidate(
      LambdaQueryWrapper<QuoteCostRunVersion> scope,
      Collection<String> sourceStatuses) {
    scope.select(QuoteCostRunVersion::getId)
        .in(QuoteCostRunVersion::getStatus, sourceStatuses)
        .orderByAsc(QuoteCostRunVersion::getId);
    List<QuoteCostRunVersion> candidates = versionMapper.selectList(scope);
    if (candidates == null || candidates.isEmpty()) {
      return 0;
    }
    List<Long> ids =
        candidates.stream()
            .map(QuoteCostRunVersion::getId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .toList();
    int affected = 0;
    for (List<Long> chunk : chunks(ids)) {
      QuoteCostRunVersion versionPatch = new QuoteCostRunVersion();
      versionPatch.setStatus(STATUS_STALE);
      int changed =
          versionMapper.update(
              versionPatch,
              Wrappers.<QuoteCostRunVersion>lambdaUpdate()
                  .in(QuoteCostRunVersion::getId, chunk)
                  .in(QuoteCostRunVersion::getStatus, sourceStatuses));
      if (changed <= 0) {
        continue;
      }
      affected += changed;
      CostRunResult resultPatch = new CostRunResult();
      resultPatch.setResultStatus(STATUS_STALE);
      resultMapper.update(
          resultPatch,
          Wrappers.<CostRunResult>lambdaUpdate()
              .in(CostRunResult::getCostRunVersionId, chunk)
              .in(CostRunResult::getResultStatus, sourceStatuses));
    }
    return affected;
  }

  private List<List<Long>> chunks(List<Long> ids) {
    List<List<Long>> chunks = new ArrayList<>();
    for (int start = 0; start < ids.size(); start += CHUNK_SIZE) {
      chunks.add(ids.subList(start, Math.min(start + CHUNK_SIZE, ids.size())));
    }
    return chunks;
  }

  private String required(String field, String value) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }
}
