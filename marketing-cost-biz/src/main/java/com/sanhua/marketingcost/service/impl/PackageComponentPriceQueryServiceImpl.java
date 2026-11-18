package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.service.pricing.PriceModelSearch;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentGapQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPricePageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentPriceQueryRequest;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotPageResponse;
import com.sanhua.marketingcost.dto.packagecomponent.PackageComponentSnapshotQueryRequest;
import com.sanhua.marketingcost.entity.PackageComponentGapItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentSnapshot;
import com.sanhua.marketingcost.mapper.PackageComponentGapItemMapper;
import com.sanhua.marketingcost.mapper.PackageComponentPriceMapper;
import com.sanhua.marketingcost.mapper.PackageComponentSnapshotMapper;
import com.sanhua.marketingcost.service.PackageComponentPriceQueryService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentPriceQueryServiceImpl implements PackageComponentPriceQueryService {

  private static final int DEFAULT_PAGE = 1;
  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 500;

  private final PackageComponentPriceMapper priceMapper;
  private final PackageComponentSnapshotMapper snapshotMapper;
  private final PackageComponentGapItemMapper gapItemMapper;

  public PackageComponentPriceQueryServiceImpl(
      PackageComponentPriceMapper priceMapper,
      PackageComponentSnapshotMapper snapshotMapper,
      PackageComponentGapItemMapper gapItemMapper) {
    this.priceMapper = priceMapper;
    this.snapshotMapper = snapshotMapper;
    this.gapItemMapper = gapItemMapper;
  }

  @Override
  public PackageComponentPricePageResponse pagePrices(PackageComponentPriceQueryRequest request) {
    PackageComponentPriceQueryRequest safe =
        request == null ? new PackageComponentPriceQueryRequest() : request;
    Page<PackageComponentPrice> page =
        priceMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildPriceQuery(safe)
                .orderByDesc(PackageComponentPrice::getGeneratedAt)
                .orderByDesc(PackageComponentPrice::getId));
    return new PackageComponentPricePageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public PackageComponentSnapshotPageResponse pageSnapshots(
      PackageComponentSnapshotQueryRequest request) {
    PackageComponentSnapshotQueryRequest safe =
        request == null ? new PackageComponentSnapshotQueryRequest() : request;
    Page<PackageComponentSnapshot> page =
        snapshotMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildSnapshotQuery(safe)
                .orderByDesc(PackageComponentSnapshot::getLockedAt)
                .orderByDesc(PackageComponentSnapshot::getId));
    return new PackageComponentSnapshotPageResponse(page.getTotal(), page.getRecords());
  }

  @Override
  public PackageComponentGapPageResponse pageGaps(PackageComponentGapQueryRequest request) {
    PackageComponentGapQueryRequest safe =
        request == null ? new PackageComponentGapQueryRequest() : request;
    Page<PackageComponentGapItem> page =
        gapItemMapper.selectPage(
            new Page<>(pageNo(safe.getPage()), pageSize(safe.getPageSize())),
            buildGapQuery(safe)
                .orderByDesc(PackageComponentGapItem::getCreatedAt)
                .orderByDesc(PackageComponentGapItem::getId));
    return new PackageComponentGapPageResponse(page.getTotal(), page.getRecords());
  }

  private LambdaQueryWrapper<PackageComponentPrice> buildPriceQuery(
      PackageComponentPriceQueryRequest request) {
    LambdaQueryWrapper<PackageComponentPrice> query = Wrappers.lambdaQuery();
    eqIfText(query, PackageComponentPrice::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PackageComponentPrice::getPackageMaterialCode, request.getPackageMaterialCode());
    PriceModelSearch.applyMaster(query, request.getModel(), "lp_package_component_price.package_material_code", "lp_package_component_price.price_org_code");
    eqIfText(query, PackageComponentPrice::getSourceTopProductCode, request.getTopProductCode());
    eqIfText(query, PackageComponentPrice::getPriceStatus, request.getPriceStatus());
    return query;
  }

  private LambdaQueryWrapper<PackageComponentSnapshot> buildSnapshotQuery(
      PackageComponentSnapshotQueryRequest request) {
    LambdaQueryWrapper<PackageComponentSnapshot> query = Wrappers.lambdaQuery();
    eqIfText(query, PackageComponentSnapshot::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PackageComponentSnapshot::getPackageMaterialCode, request.getPackageMaterialCode());
    PriceModelSearch.applyMaster(query, request.getModel(), "lp_package_component_snapshot.package_material_code", "lp_package_component_snapshot.price_org_code");
    eqIfText(query, PackageComponentSnapshot::getStatus, request.getStatus());
    return query;
  }

  private LambdaQueryWrapper<PackageComponentGapItem> buildGapQuery(
      PackageComponentGapQueryRequest request) {
    LambdaQueryWrapper<PackageComponentGapItem> query = Wrappers.lambdaQuery();
    eqIfText(query, PackageComponentGapItem::getPeriodMonth, request.getPeriodMonth());
    eqIfText(query, PackageComponentGapItem::getPackageMaterialCode, request.getPackageMaterialCode());
    String model = PriceModelSearch.normalize(request.getModel());
    if (!model.isEmpty()) query.apply("EXISTS (SELECT 1 FROM lp_package_component_snapshot model_snapshot"
        + " WHERE model_snapshot.business_unit_type=lp_package_component_gap_item.business_unit_type"
        + " AND model_snapshot.period_month=lp_package_component_gap_item.period_month"
        + " AND model_snapshot.package_material_code=lp_package_component_gap_item.package_material_code AND "
        + PriceModelSearch.masterCondition("model_snapshot.package_material_code", "model_snapshot.price_org_code", "{0}") + ")", model);
    eqIfText(query, PackageComponentGapItem::getGapType, request.getGapType());
    eqIfText(query, PackageComponentGapItem::getOaPushStatus, request.getOaPushStatus());
    return query;
  }

  private <T> void eqIfText(
      LambdaQueryWrapper<T> query, com.baomidou.mybatisplus.core.toolkit.support.SFunction<T, ?> column,
      String value) {
    if (StringUtils.hasText(value)) {
      query.eq(column, value.trim());
    }
  }

  private long pageNo(Integer page) {
    return page == null || page < 1 ? DEFAULT_PAGE : page;
  }

  private long pageSize(Integer pageSize) {
    if (pageSize == null || pageSize < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(pageSize, MAX_PAGE_SIZE);
  }
}
