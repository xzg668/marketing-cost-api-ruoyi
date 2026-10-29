package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.PackagePriceRequest;
import com.sanhua.marketingcost.dto.PackagePriceResult;
import com.sanhua.marketingcost.dto.priceprepare.PackageComponentPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.entity.BomCostingRow;
import com.sanhua.marketingcost.entity.PackageComponentPrice;
import com.sanhua.marketingcost.entity.PackageComponentPriceDetail;
import com.sanhua.marketingcost.service.PackageComponentPricePrepareStrategy;
import com.sanhua.marketingcost.service.PackageComponentPriceService;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PackageComponentPricePrepareStrategyImpl
    implements PackageComponentPricePrepareStrategy {

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
    return execute(
        prepareNo, oaNo, periodMonth, null, bomPurpose, sourceType, planItem, true, null);
  }

  @Override
  public PackageComponentPricePrepareResult calculate(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem) {
    return execute(
        prepareNo, oaNo, periodMonth, priceAsOfTime, bomPurpose, sourceType, planItem, false, null);
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
    return execute(
        prepareNo, oaNo, periodMonth, priceAsOfTime, bomPurpose, sourceType, planItem, true, null);
  }

  @Override
  public PackageComponentPricePrepareResult prepare(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem,
      PricePrepareScenarioContext scenarioContext) {
    return execute(
        prepareNo,
        oaNo,
        periodMonth,
        priceAsOfTime,
        bomPurpose,
        sourceType,
        planItem,
        true,
        scenarioContext);
  }

  @Override
  public PackageComponentPricePrepareResult calculate(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem,
      PricePrepareScenarioContext scenarioContext) {
    return execute(
        prepareNo,
        oaNo,
        periodMonth,
        priceAsOfTime,
        bomPurpose,
        sourceType,
        planItem,
        false,
        scenarioContext);
  }

  private PackageComponentPricePrepareResult execute(
      String prepareNo,
      String oaNo,
      String periodMonth,
      LocalDateTime priceAsOfTime,
      String bomPurpose,
      String sourceType,
      PricePreparePlanItem planItem,
      boolean persist,
      PricePrepareScenarioContext scenarioContext) {
    String packageMaterialCode = planItem == null ? null : trimToNull(planItem.getMaterialCode());
    String topProductCode = planItem == null ? null : trimToNull(planItem.getTopProductCode());
    if (packageMaterialCode == null || topProductCode == null) {
      List<PackageComponentPricePrepareResult.Gap> gaps =
          List.of(
              new PackageComponentPricePrepareResult.Gap(
                  GAP_TYPE_MISSING_STRUCTURE,
                  packageMaterialCode,
                  "lp_bom_costing_row",
                  "包装组件价格准备缺少顶层产品或包装父料号上下文"));
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_STRUCTURE, "包装组件价格准备缺少必要上下文", gaps);
    }

    PackagePriceRequest request = new PackagePriceRequest();
    var parent = java.util.Objects.requireNonNull(planItem.getBomRow(), "包装准备缺少报价 BOM 行");
    if (scenarioContext == null) scenarioContext = PricePrepareScenarioContext.oaLocked(parent.getOaFormItemId(), requiredPriceOrgCode(planItem));
    request.setOaFormItemId(parent.getOaFormItemId());
    request.setHouseholdChildPaths(parent.getHouseholdChildPaths());
    if (!parent.getHouseholdChildPaths().isEmpty()) {
      request.setSourceParentPath(parent.getCurrentSourcePath());
      request.setSourceParentHierarchyId(parent.getRawHierarchyNodeId());
    }
    request.setMonthlyPrices(scenarioContext.monthlyPrices());
    request.setPackageMaterialCode(packageMaterialCode);
    request.setBusinessUnitType(planItem.getBomRow().getBusinessUnitType());
    if (scenarioContext != null) {
      request.setPriceScenarioType(scenarioContext.scenarioType());
      request.setVariableOverrides(scenarioContext.variableOverrides());
    }
    if (scenarioContext != null)
      request.setSupplierPriceReviewContext(scenarioContext.supplierReviews());
    request.setPriceOrgCode(requiredPriceOrgCode(planItem));
    request.setPeriodMonth(periodMonth);
    request.setOaNo(oaNo);
    request.setTopProductCode(topProductCode);
    request.setBomPurpose(bomPurpose);
    request.setSourceType(sourceType);
    // 月度调价由批次固化 price_as_of_time；普通价格准备未传时继续沿用当前日期。
    request.setAsOfDate(
        priceAsOfTime == null
            ? CostPricingPeriodUtils.currentPricingDate()
            : priceAsOfTime.toLocalDate());
    request.setPriceAsOfTime(priceAsOfTime);
    request.setCalcBatchId(prepareNo);

    PackagePriceResult priceResult =
        persist
            ? packageComponentPriceService.ensurePrice(request)
            : packageComponentPriceService.calculatePrice(request);
    if (priceResult == null || priceResult.getPrice() == null) {
      List<PackageComponentPricePrepareResult.Gap> gaps =
          List.of(
              new PackageComponentPricePrepareResult.Gap(
                  GAP_TYPE_MISSING_STRUCTURE,
                  packageMaterialCode,
                  "PackageComponentPriceService",
                  "包装组件价格服务未返回价格结果"));
      return PackageComponentPricePrepareResult.notReady(STATUS_FAILED, "包装组件价格服务未返回价格结果", gaps);
    }

    PackageComponentPrice price = priceResult.getPrice();
    if (priceResult.isComplete()
        && PRICE_STATUS_PRICED.equals(price.getPriceStatus())
        && price.getTotalPrice() != null) {
      BigDecimal amount =
          quantity(planItem) == null ? null : price.getTotalPrice().multiply(quantity(planItem));
      var prepared =
          PackageComponentPricePrepareResult.ready(
              price.getTotalPrice(), amount, price.getId(), "包装组件价格准备完成");
      var json =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .findAndRegisterModules()
              .enable(
                  com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
      recordBaseUses(planItem, scenarioContext, priceResult, new MonthlyPriceDocumentCodec(json));
      prepared.setInputSnapshotJson(
          new PackageCalculationEvidence(json).capture(price, priceResult.getDetails()));
      return prepared;
    }

    if (PRICE_STATUS_MISSING_STRUCTURE.equals(price.getPriceStatus())) {
      List<PackageComponentPricePrepareResult.Gap> gaps =
          List.of(
              new PackageComponentPricePrepareResult.Gap(
                  GAP_TYPE_MISSING_STRUCTURE,
                  packageMaterialCode,
                  "PackageComponentSnapshotService",
                  message(priceResult, "包装组件缺结构，无法生成价格")));
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_STRUCTURE, message(priceResult, "包装组件缺结构，无法生成价格"), gaps);
    }

    if (PRICE_STATUS_MISSING_CHILD_PRICE.equals(price.getPriceStatus())) {
      List<PackageComponentPricePrepareResult.Gap> gaps =
          childPriceGaps(packageMaterialCode, priceResult);
      return PackageComponentPricePrepareResult.notReady(
          STATUS_MISSING_PRICE, message(priceResult, "包装组件存在子件缺价，当前阶段只记录不阻断"), gaps);
    }

    List<PackageComponentPricePrepareResult.Gap> gaps =
        List.of(
            new PackageComponentPricePrepareResult.Gap(
                GAP_TYPE_MISSING_PRICE,
                packageMaterialCode,
                "PackageComponentPriceService",
                message(priceResult, "包装组件价格状态异常：" + price.getPriceStatus())));
    return PackageComponentPricePrepareResult.notReady(
        STATUS_FAILED, message(priceResult, "包装组件价格状态异常：" + price.getPriceStatus()), gaps);
  }

  private void recordBaseUses(PricePreparePlanItem plan, PricePrepareScenarioContext scenario,
      PackagePriceResult result, MonthlyPriceDocumentCodec codec) {
    var session = java.util.Objects.requireNonNull(scenario.monthlyPrices().get(), "包装件缺少月价输入会话");
    var parentQuantity = java.util.Objects.requireNonNull(quantity(plan), "包装母件缺少实际用量");
    String parentPath = MonthlyBomPricingScope.pathKey(plan.getBomRow().getPath());
    BigDecimal base = result.getPrice().getPackageParentBaseQty();
    for (var detail : result.getDetails()) {
      if (detail.getHouseholdUse() != null) continue;
      BigDecimal use = detail.getQtyPerParent();
      if (base != null && base.signum() != 0) use = use.divide(base, 8, java.math.RoundingMode.HALF_UP);
      use = use.multiply(parentQuantity);
      if (use.signum() == 0) continue;
      var captured = codec.decode(detail.getInputSnapshotJson(), MonthlyPreparationPrice.class);
      var expected = scenario.scenarioType() == com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE
          ? captured.financialPrice() : captured.customerPrice();
      if (expected.compareTo(detail.getChildUnitPrice()) != 0)
        throw new IllegalArgumentException("包装基础价格与捕获月价不一致");
      session.consume(parentPath + "/pack-" + detail.getLineNo(), captured.value(), use.abs());
    }
  }

  private List<PackageComponentPricePrepareResult.Gap> childPriceGaps(
      String packageMaterialCode, PackagePriceResult priceResult) {
    List<PackageComponentPricePrepareResult.Gap> gaps = new ArrayList<>();
    if (priceResult.getDetails() != null) {
      for (PackageComponentPriceDetail detail : priceResult.getDetails()) {
        if (detail == null || PRICE_STATUS_PRICED.equals(detail.getPriceStatus())
            || (PackageComponentPriceDetail.HOUSEHOLD_EXCLUDED.equals(detail.getPriceStatus())
                && detail.getHouseholdUse() != null)) {
          continue;
        }
        String childCode = trimToNull(detail.getChildMaterialCode());
        String message =
            StringUtils.hasText(detail.getMissingReason())
                ? detail.getMissingReason().trim()
                : "包装组件子件缺价";
        var gap =
            new PackageComponentPricePrepareResult.Gap(
                GAP_TYPE_MISSING_PRICE,
                childCode == null ? packageMaterialCode : childCode,
                childCode == null ? null : detail.getChildMaterialName(),
                "lp_package_component_price_detail",
                message);
        gap.setPriceType(detail.getPriceType());
        gap.setReasonCode(detail.getFailureCode());
        gaps.add(gap);
      }
    }
    if (gaps.isEmpty()) {
      gaps.add(
          new PackageComponentPricePrepareResult.Gap(
              GAP_TYPE_MISSING_PRICE,
              packageMaterialCode,
              "PackageComponentPriceService",
              message(priceResult, "包装组件存在子件缺价")));
    }
    return gaps;
  }

  private String message(PackagePriceResult priceResult, String fallback) {
    if (priceResult != null
        && priceResult.getWarnings() != null
        && !priceResult.getWarnings().isEmpty()) {
      return String.join("；", priceResult.getWarnings());
    }
    return fallback;
  }

  private BigDecimal quantity(PricePreparePlanItem planItem) {
    return planItem == null || planItem.getBomRow() == null
        ? null
        : planItem.getBomRow().getQtyPerTop();
  }

  private String requiredPriceOrgCode(PricePreparePlanItem planItem) {
    BomCostingRow row = planItem == null ? null : planItem.getBomRow();
    String priceOrgCode = trimToNull(row == null ? null : row.getPriceOrgCode());
    if (priceOrgCode == null) {
      throw new IllegalStateException("包装组件价格准备缺少上游 priceOrgCode");
    }
    return priceOrgCode;
  }

  private String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
