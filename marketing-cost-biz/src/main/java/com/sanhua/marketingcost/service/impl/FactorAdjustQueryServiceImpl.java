package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.FactorAdjustBatchDetailDto;
import com.sanhua.marketingcost.dto.FactorAdjustBatchDto;
import com.sanhua.marketingcost.dto.FactorAdjustBatchPageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustBatchQueryRequest;
import com.sanhua.marketingcost.dto.FactorAdjustPriceDto;
import com.sanhua.marketingcost.dto.FactorAdjustPricePageResponse;
import com.sanhua.marketingcost.dto.FactorAdjustPriceQueryRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListPageResponse;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListQueryRequest;
import com.sanhua.marketingcost.dto.FactorMonthlyPriceListRowDto;
import com.sanhua.marketingcost.entity.FactorAdjustBatch;
import com.sanhua.marketingcost.entity.FactorAdjustPrice;
import com.sanhua.marketingcost.entity.FactorIdentity;
import com.sanhua.marketingcost.entity.FactorMonthlyPrice;
import com.sanhua.marketingcost.mapper.FactorAdjustBatchMapper;
import com.sanhua.marketingcost.mapper.FactorAdjustPriceMapper;
import com.sanhua.marketingcost.mapper.FactorIdentityMapper;
import com.sanhua.marketingcost.mapper.FactorMonthlyPriceMapper;
import com.sanhua.marketingcost.service.FactorAdjustQueryService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class FactorAdjustQueryServiceImpl implements FactorAdjustQueryService {

  private final FactorAdjustBatchMapper adjustBatchMapper;
  private final FactorAdjustPriceMapper adjustPriceMapper;
  private final FactorIdentityMapper factorIdentityMapper;
  private final FactorMonthlyPriceMapper monthlyPriceMapper;

  public FactorAdjustQueryServiceImpl(
      FactorAdjustBatchMapper adjustBatchMapper,
      FactorAdjustPriceMapper adjustPriceMapper,
      FactorIdentityMapper factorIdentityMapper,
      FactorMonthlyPriceMapper monthlyPriceMapper) {
    this.adjustBatchMapper = adjustBatchMapper;
    this.adjustPriceMapper = adjustPriceMapper;
    this.factorIdentityMapper = factorIdentityMapper;
    this.monthlyPriceMapper = monthlyPriceMapper;
  }

  @Override
  public FactorAdjustBatchPageResponse pageBatches(FactorAdjustBatchQueryRequest request) {
    FactorAdjustBatchQueryRequest req = request == null ? new FactorAdjustBatchQueryRequest() : request;
    LambdaQueryWrapper<FactorAdjustBatch> query = Wrappers.lambdaQuery(FactorAdjustBatch.class)
        .eq(FactorAdjustBatch::getDeleted, 0);
    eqText(query, FactorAdjustBatch::getPricingMonth, req.getPricingMonth());
    eqText(query, FactorAdjustBatch::getBusinessUnitType, req.getBusinessUnitType());
    likeText(query, FactorAdjustBatch::getAdjustBatchNo, req.getAdjustBatchNo());
    eqText(query, FactorAdjustBatch::getAdjustType, req.getAdjustType());
    eqText(query, FactorAdjustBatch::getUsageScope, req.getUsageScope());
    eqText(query, FactorAdjustBatch::getStatus, req.getStatus());
    if (!Boolean.TRUE.equals(req.getIncludeAllUploaders())) {
      eqText(query, FactorAdjustBatch::getUploadedBy, req.getUploadedBy());
    } else if (StringUtils.hasText(req.getUploadedBy())) {
      query.eq(FactorAdjustBatch::getUploadedBy, req.getUploadedBy().trim());
    }
    query.orderByDesc(FactorAdjustBatch::getUploadedAt)
        .orderByDesc(FactorAdjustBatch::getId);

    Page<FactorAdjustBatch> page = adjustBatchMapper.selectPage(
        new Page<>(page(req.getPage()), size(req.getPageSize(), req.getLimit())), query);
    return new FactorAdjustBatchPageResponse(
        page.getTotal(),
        page.getRecords().stream().map(FactorAdjustBatchDto::fromEntity).toList());
  }

  @Override
  public FactorAdjustBatchDetailDto getBatchDetail(Long adjustBatchId) {
    if (adjustBatchId == null) {
      return null;
    }
    FactorAdjustBatch batch = adjustBatchMapper.selectById(adjustBatchId);
    if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
      return null;
    }
    FactorAdjustBatchDetailDto detail = new FactorAdjustBatchDetailDto();
    detail.setBatch(FactorAdjustBatchDto.fromEntity(batch));
    detail.getPrices().addAll(pagePrices(priceQuery(adjustBatchId, 1, 1000)).getList());
    return detail;
  }

  @Override
  public FactorAdjustPricePageResponse pagePrices(FactorAdjustPriceQueryRequest request) {
    FactorAdjustPriceQueryRequest req = request == null ? new FactorAdjustPriceQueryRequest() : request;
    LambdaQueryWrapper<FactorAdjustPrice> query = Wrappers.lambdaQuery(FactorAdjustPrice.class)
        .eq(FactorAdjustPrice::getDeleted, 0);
    if (req.getAdjustBatchId() != null) {
      query.eq(FactorAdjustPrice::getAdjustBatchId, req.getAdjustBatchId());
    }
    if (req.getFactorIdentityId() != null) {
      query.eq(FactorAdjustPrice::getFactorIdentityId, req.getFactorIdentityId());
    }
    eqText(query, FactorAdjustPrice::getStatus, req.getStatus());
    if (StringUtils.hasText(req.getKeyword())) {
      String like = req.getKeyword().trim();
      query.and(w -> w.like(FactorAdjustPrice::getFactorSeqNo, like)
          .or().like(FactorAdjustPrice::getFactorName, like)
          .or().like(FactorAdjustPrice::getShortName, like)
          .or().like(FactorAdjustPrice::getPriceSource, like));
    }
    query.orderByAsc(FactorAdjustPrice::getSourceRowNumber)
        .orderByAsc(FactorAdjustPrice::getId);
    Page<FactorAdjustPrice> page = adjustPriceMapper.selectPage(
        new Page<>(page(req.getPage()), size(req.getPageSize(), req.getLimit())), query);
    return new FactorAdjustPricePageResponse(
        page.getTotal(),
        page.getRecords().stream().map(FactorAdjustPriceDto::fromEntity).toList());
  }

  @Override
  public FactorMonthlyPriceListPageResponse pageMonthlyPrices(FactorMonthlyPriceListQueryRequest request) {
    FactorMonthlyPriceListQueryRequest req =
        request == null ? new FactorMonthlyPriceListQueryRequest() : request;
    String month = required("pricingMonth", req.getPricingMonth());
    String businessUnitType = required("businessUnitType", req.getBusinessUnitType());
    LambdaQueryWrapper<FactorIdentity> query = Wrappers.lambdaQuery(FactorIdentity.class)
        .eq(FactorIdentity::getBusinessUnitType, businessUnitType)
        .eq(FactorIdentity::getStatus, "ACTIVE");
    if (StringUtils.hasText(req.getKeyword())) {
      String like = req.getKeyword().trim();
      query.and(w -> w.like(FactorIdentity::getFactorSeqNo, like)
          .or().like(FactorIdentity::getFactorName, like)
          .or().like(FactorIdentity::getShortName, like)
          .or().like(FactorIdentity::getPriceSource, like));
    }
    query.orderByAsc(FactorIdentity::getFactorSeqNo)
        .orderByAsc(FactorIdentity::getId);

    Page<FactorIdentity> page = factorIdentityMapper.selectPage(
        new Page<>(page(req.getPage()), size(req.getPageSize(), null)), query);
    List<FactorMonthlyPriceListRowDto> rows = new ArrayList<>();
    for (FactorIdentity identity : page.getRecords()) {
      FactorMonthlyPrice monthlyPrice = findMonthlyPrice(identity.getId(), month);
      FactorMonthlyPriceListRowDto row = toMonthlyRow(identity, monthlyPrice, month, businessUnitType);
      applyLatestAdjust(row, req.getLatestAdjustUsageScope(), req.getLatestAdjustedBy());
      if (matchesMonthlyFilter(row, req)) {
        rows.add(row);
      }
    }
    return new FactorMonthlyPriceListPageResponse(page.getTotal(), rows);
  }

  private FactorMonthlyPrice findMonthlyPrice(Long factorIdentityId, String month) {
    return monthlyPriceMapper.selectOne(Wrappers.lambdaQuery(FactorMonthlyPrice.class)
        .eq(FactorMonthlyPrice::getFactorIdentityId, factorIdentityId)
        .eq(FactorMonthlyPrice::getPriceMonth, month)
        .eq(FactorMonthlyPrice::getStatus, "ACTIVE")
        .last("LIMIT 1"));
  }

  private FactorMonthlyPriceListRowDto toMonthlyRow(
      FactorIdentity identity,
      FactorMonthlyPrice monthlyPrice,
      String month,
      String businessUnitType) {
    FactorMonthlyPriceListRowDto row = new FactorMonthlyPriceListRowDto();
    row.setFactorIdentityId(identity.getId());
    row.setBusinessUnitType(businessUnitType);
    row.setFactorSeqNo(identity.getFactorSeqNo());
    row.setFactorName(identity.getFactorName());
    row.setShortName(identity.getShortName());
    row.setPriceSource(identity.getPriceSource());
    row.setPriceMonth(month);
    if (monthlyPrice != null) {
      row.setFactorMonthlyPriceId(monthlyPrice.getId());
      row.setDailyEffectivePrice(monthlyPrice.getPrice());
      row.setTaxIncluded(monthlyPrice.getTaxIncluded());
      row.setSourceTag(monthlyPrice.getSourceTag());
      row.setSourceUploadBatchId(monthlyPrice.getSourceUploadBatchId());
      row.setLatestAdjustBatchId(monthlyPrice.getLatestAdjustBatchId());
      row.setLatestAdjustedBy(monthlyPrice.getLatestAdjustedBy());
      row.setLatestAdjustedAt(monthlyPrice.getLatestAdjustedAt());
    }
    return row;
  }

  private void applyLatestAdjust(
      FactorMonthlyPriceListRowDto row, String usageScope, String uploadedBy) {
    LambdaQueryWrapper<FactorAdjustPrice> query = Wrappers.lambdaQuery(FactorAdjustPrice.class)
        .eq(FactorAdjustPrice::getFactorIdentityId, row.getFactorIdentityId())
        .eq(FactorAdjustPrice::getDeleted, 0)
        .ne(FactorAdjustPrice::getStatus, "FAILED")
        .orderByDesc(FactorAdjustPrice::getId)
        .last("LIMIT 50");
    for (FactorAdjustPrice price : adjustPriceMapper.selectList(query)) {
      FactorAdjustBatch batch = adjustBatchMapper.selectById(price.getAdjustBatchId());
      if (batch == null || Integer.valueOf(1).equals(batch.getDeleted())) {
        continue;
      }
      if (!Objects.equals(row.getPriceMonth(), normalize(batch.getPricingMonth()))
          || !Objects.equals(row.getBusinessUnitType(), normalize(batch.getBusinessUnitType()))) {
        continue;
      }
      if (StringUtils.hasText(usageScope)
          && !normalize(usageScope).equals(normalize(batch.getUsageScope()))) {
        continue;
      }
      if (StringUtils.hasText(uploadedBy)
          && !normalize(uploadedBy).equals(normalize(batch.getUploadedBy()))) {
        continue;
      }
      row.setLatestAdjustBatchId(batch.getId());
      row.setLatestAdjustBatchNo(batch.getAdjustBatchNo());
      row.setLatestAdjustUsageScope(batch.getUsageScope());
      row.setLatestAdjustPrice(price.getAdjustedPrice());
      row.setLatestAdjustOriginalPrice(price.getOriginalPrice());
      row.setLatestAdjustDelta(price.getPriceDelta());
      row.setLatestAdjustChangeRate(price.getChangeRate());
      row.setLatestAdjustStatus(price.getStatus());
      row.setLatestAdjustedBy(batch.getUploadedBy());
      row.setLatestAdjustedAt(batch.getUploadedAt());
      row.setUnit(price.getUnit());
      return;
    }
  }

  private boolean matchesMonthlyFilter(
      FactorMonthlyPriceListRowDto row, FactorMonthlyPriceListQueryRequest req) {
    if (StringUtils.hasText(req.getSourceTag())
        && !normalize(req.getSourceTag()).equals(normalize(row.getSourceTag()))) {
      return false;
    }
    if (StringUtils.hasText(req.getLatestAdjustUsageScope())
        && !normalize(req.getLatestAdjustUsageScope()).equals(normalize(row.getLatestAdjustUsageScope()))) {
      return false;
    }
    if (StringUtils.hasText(req.getLatestAdjustedBy())
        && !normalize(req.getLatestAdjustedBy()).equals(normalize(row.getLatestAdjustedBy()))) {
      return false;
    }
    return true;
  }

  private FactorAdjustPriceQueryRequest priceQuery(Long adjustBatchId, int page, int pageSize) {
    FactorAdjustPriceQueryRequest request = new FactorAdjustPriceQueryRequest();
    request.setAdjustBatchId(adjustBatchId);
    request.setPage(page);
    request.setPageSize(pageSize);
    return request;
  }

  private <T> void eqText(
      LambdaQueryWrapper<T> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private <T> void likeText(
      LambdaQueryWrapper<T> query,
      com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.like(column, value.trim());
    }
  }

  private int page(Integer page) {
    return page == null || page < 1 ? 1 : page;
  }

  private int size(Integer pageSize, Integer limit) {
    Integer resolved = pageSize != null ? pageSize : limit;
    if (resolved == null || resolved < 1) {
      return 20;
    }
    return Math.min(resolved, 1000);
  }

  private String required(String field, String value) {
    String normalized = normalize(value);
    if (!StringUtils.hasText(normalized)) {
      throw new IllegalArgumentException(field + " 必填");
    }
    return normalized;
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
