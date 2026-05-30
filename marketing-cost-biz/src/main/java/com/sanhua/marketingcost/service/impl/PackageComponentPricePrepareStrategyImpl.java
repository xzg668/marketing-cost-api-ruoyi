package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentPricePrepareStrategyImpl implements PackageComponentPricePrepareStrategy {

  static final String STATUS_READY = "READY";
  static final String STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  static final String STATUS_MISSING_PRICE = "MISSING_PRICE";
  static final String STATUS_FAILED = "FAILED";
  static final String PRICE_STATUS_PRICED = "PRICED";
  static final String PRICE_STATUS_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  static final String PRICE_STATUS_MISSING_CHILD_PRICE = "MISSING_CHILD_PRICE";
  static final String GAP_TYPE_MISSING_STRUCTURE = "MISSING_STRUCTURE";
  static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";

  private final PackageComponentPriceService packageComponentPriceService;

  public PackageComponentPricePrepareStrategyImpl(
      PackageComponentPriceService packageComponentPriceService) {
    this.packageComponentPriceService = packageComponentPriceService;
  }

  @Override
  public PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem) {
    return prepare(prepareNo, oaNo, periodMonth, null, bomPurpose, sourceType, planItem);
  }

  @Override
  public PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem) {
    String packageMaterialCode = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    String topProductCode = planItem == null ? null : trimToNull(planItem.getTopProductCode());
    if (packageMaterialCode == null || topProductCode == null) {
      List<PackageComponentPricePrepareResult.Gap> gaps = List.of(
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE,
              packageMaterialCode,
              "lp_bom_costing_row",
              "包装组件价格准备缺少顶层产品或包装父料号上下文"));
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_STRUCTURE, "包装组件价格准备缺少必要上下文", gaps);
    }

    PackagePriceRequest request = new PackagePriceRequest();
    request.setPackageMaterialCode(packageMaterialCode);
    request.setPeriodMonth(periodMonth);
    request.setOaNo(oaNo);
    request.setTopProductCode(topProductCode);
    request.setBomPurpose(bomPurpose);
    request.setSourceType(sourceType);
    // 月度调价由批次固化 price_as_of_time；普通价格准备未传时继续沿用当前日期。
    request.setAsOfDate(priceAsOfTime == null ? LocalDate.now() : priceAsOfTime.toLocalDate());
    request.setPriceAsOfTime(priceAsOfTime);
    request.setCalcBatchId(prepareNo);
    request.setForceRefresh(true);

    PackagePriceResult priceResult = packageComponentPriceService.ensurePrice(request);
    if (priceResult == null || priceResult.getPrice() == null) {
      List<PackageComponentPricePrepareResult.Gap> gaps = List.of(
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE,
              packageMaterialCode,
              "PackageComponentPriceService",
              "包装组件价格服务未返回价格结果"));
      return PackageComponentPricePrepareResult.notReady(
          STATUS_FAILED, "包装组件价格服务未返回价格结果", gaps);
    }

    PackageComponentPrice price = priceResult.getPrice();
    if (priceResult.isComplete()
        && PRICE_STATUS_PRICED.equals(price.getPriceStatus())
        && price.getTotalPrice() != null) {
      BigDecimal amount = quantity(planItem) == null ? null : price.getTotalPrice().multiply(quantity(planItem));
      return PackageComponentPricePrepareResult.ready(
          price.getTotalPrice(),
          amount,
          price.getId(),
          "包装组件价格准备完成");
    }

    if (PRICE_STATUS_MISSING_STRUCTURE.equals(price.getPriceStatus())) {
      List<PackageComponentPricePrepareResult.Gap> gaps = List.of(
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_STRUCTURE,
              packageMaterialCode,
              "PackageComponentSnapshotService",
              message(priceResult, "包装组件缺结构，无法生成价格")));
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_STRUCTURE, message(priceResult, "包装组件缺结构，无法生成价格"), gaps);
    }

    if (PRICE_STATUS_MISSING_CHILD_PRICE.equals(price.getPriceStatus())) {
      List<PackageComponentPricePrepareResult.Gap> gaps = childPriceGaps(packageMaterialCode, priceResult);
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_PRICE,
          message(priceResult, "包装组件存在子件缺价，当前阶段只记录不阻断"),
          gaps);
    }

    List<PackageComponentPricePrepareResult.Gap> gaps = List.of(
        new PackageComponentPricePrepareResult.Gap(
            GAP_TYPE_MISSING_PRICE,
            packageMaterialCode,
            "PackageComponentPriceService",
            message(priceResult, "包装组件价格状态异常：" + price.getPriceStatus())));
    return PackageComponentPricePrepareResult.notReady(
        STATUS_FAILED, message(priceResult, "包装组件价格状态异常：" + price.getPriceStatus()), gaps);
  }

  private List<PackageComponentPricePrepareResult.Gap> childPriceGaps(
      String packageMaterialCode,
      PackagePriceResult priceResult) {
    List<PackageComponentPricePrepareResult.Gap> gaps = new ArrayList<>();
    if (priceResult.getDetails() != null) {
      for (PackageComponentPriceDetail detail : priceResult.getDetails()) {
        if (detail == null || PRICE_STATUS_PRICED.equals(detail.getPriceStatus())) {
          continue;
        }
        String childCode = trimToNull(detail.getChildMaterialCode());
        String message = StringUtils.hasText(detail.getMissingReason())
            ? detail.getMissingReason().trim()
            : "包装组件子件缺价";
        gaps.add(new PackageComponentPricePrepareResult.Gap(
            GAP_TYPE_MISSING_PRICE,
            childCode == null ? packageMaterialCode : childCode,
            "lp_package_component_price_detail",
            message));
      }
    }
    if (gaps.isEmpty()) {
      gaps.add(new PackageComponentPricePrepareResult.Gap(
          GAP_TYPE_MISSING_PRICE,
          packageMaterialCode,
          "PackageComponentPriceService",
          message(priceResult, "包装组件存在子件缺价")));
    }
    return gaps;
  }

  private String message(PackagePriceResult priceResult, String fallback) {
    if (priceResult != null && priceResult.getWarnings() != null && !priceResult.getWarnings().isEmpty()) {
      return String.join("；", priceResult.getWarnings());
    }
    return fallback;
  }

  private BigDecimal quantity(PricePreparePlanItem planItem) {
    return planItem == null || planItem.getBomRow() == null ? null : planItem.getBomRow().getQtyPerTop();
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
