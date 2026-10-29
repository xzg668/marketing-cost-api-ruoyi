package com.sanhua.marketingcost.service.impl;

import com.sanhua.marketingcost.dto.priceprepare.NormalMaterialPricePrepareResult;
import com.sanhua.marketingcost.dto.priceprepare.PricePreparePlanItem;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import com.sanhua.marketingcost.service.NormalMaterialPricePrepareStrategy;
import com.sanhua.marketingcost.service.PricePrepareScenarioContext;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.util.CostPricingPeriodUtils;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/** Ordinary quotation occurrences consume the same captured monthly inputs as manufacturing and packaging. */
@Service
public class NormalMaterialPricePrepareStrategyImpl implements NormalMaterialPricePrepareStrategy {
  private static final String STATUS_MISSING_PRICE = "MISSING_PRICE";
  private static final String STATUS_FAILED = "FAILED";
  private static final String GAP_TYPE_MISSING_PRICE = "MISSING_PRICE";
  private final MonthlyQuotationSessions monthlySessions;
  private final MonthlyPriceDocumentCodec monthlyCodec;

  public NormalMaterialPricePrepareStrategyImpl(MonthlyQuotationSessions monthlySessions,
      MonthlyPriceDocumentCodec monthlyCodec) {
    this.monthlySessions = Objects.requireNonNull(monthlySessions);
    this.monthlyCodec = Objects.requireNonNull(monthlyCodec);
  }

  @Override public NormalMaterialPricePrepareResult prepare(String oaNo, String businessUnitType,
      String periodMonth, PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, null, null, planItem);
  }

  @Override public NormalMaterialPricePrepareResult prepare(String oaNo, String businessUnitType,
      String periodMonth, LocalDateTime priceAsOfTime, PricePreparePlanItem planItem) {
    return prepare(oaNo, businessUnitType, periodMonth, priceAsOfTime, null, planItem);
  }

  @Override public NormalMaterialPricePrepareResult prepare(String oaNo, String businessUnitType,
      String periodMonth, LocalDateTime priceAsOfTime, PricePrepareScenarioContext scenario,
      PricePreparePlanItem planItem) {
    return prepareMonthly(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenario, List.of(planItem)).get(planItem);
  }

  @Override public NormalMaterialPricePrepareResult calculate(String oaNo, String businessUnitType,
      String periodMonth, LocalDateTime priceAsOfTime, PricePrepareScenarioContext scenario,
      PricePreparePlanItem planItem) {
    return prepareMonthly(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenario, List.of(planItem)).get(planItem);
  }

  /** Adoption is owned by the preparation application operation; this strategy only records actual uses. */
  @Override public Map<PricePreparePlanItem, NormalMaterialPricePrepareResult> prepareBatch(String oaNo,
      String businessUnitType, String periodMonth, LocalDateTime priceAsOfTime,
      PricePrepareScenarioContext scenario, List<PricePreparePlanItem> items, boolean persist) {
    return items == null || items.isEmpty() ? Map.of()
        : prepareMonthly(oaNo, businessUnitType, periodMonth, priceAsOfTime, scenario, items);
  }

  private String organization(PricePreparePlanItem item) {
    var row = Objects.requireNonNull(item.getBomRow(), "普通备价必须具有本次 BOM 结算行");
    String org = Objects.requireNonNull(row.getPriceOrgCode(), "普通备价缺少取价组织");
    return org.trim();
  }

  private boolean isByproduct(PricePreparePlanItem item) {
    return "BYPRODUCT_EXTRA".equals(item.getBomRow().getSettlementRowType())
        || "副产品".equals(item.getBomRow().getShapeAttr());
  }

  private BigDecimal quantity(PricePreparePlanItem item) {
    return item.getBomRow().getQtyPerTop();
  }

  private Map<PricePreparePlanItem, NormalMaterialPricePrepareResult> prepareMonthly(
      String oaNo, String unit, String month, LocalDateTime asOf, PricePrepareScenarioContext scenario,
      List<PricePreparePlanItem> items) {
    var first = items.getFirst();
    if (scenario == null) scenario = PricePrepareScenarioContext.oaLocked(
        first.getBomRow().getOaFormItemId(), organization(first));
    var session = monthlySessions.open(oaNo, unit, month,
        asOf == null ? LocalDateTime.now(CostPricingPeriodUtils.BUSINESS_ZONE) : asOf, scenario.forOrganization(organization(first)));
    var inputs = new java.util.LinkedHashMap<PricePreparePlanItem, QuotationPriceSession.Input>();
    var results = new java.util.LinkedHashMap<PricePreparePlanItem, NormalMaterialPricePrepareResult>();
    for (var item : items) {
      var row = item.getBomRow();
      var q = quantity(item);
      if (q == null) {
        results.put(item, NormalMaterialPricePrepareResult.gap(STATUS_FAILED, GAP_TYPE_MISSING_PRICE,
            "INVALID_INPUT", MonthlyPreparationPrice.KIND, "lp_bom_costing_row", "计价物料缺少实际用量"));
      } else if (q.signum() == 0) {
        results.put(item, NormalMaterialPricePrepareResult.ready(BigDecimal.ZERO, BigDecimal.ZERO,
            "零用量", "ZERO_USAGE", null, "零用量不采用物料月价"));
      } else {
        // Source registration is consulted only after the monthly lookup misses.
        boolean scrap = isByproduct(item);
        inputs.put(item, new QuotationPriceSession.Input(item.getMaterialCode(), organization(item),
            scrap ? "KG" : row.getUnit(), row.getMaterialSpec(), scrap ? TaxBasis.EXCLUDED : null,
            null, session.conditions(organization(item)), q.abs(),
            scrap ? QuotationPriceSession.Purpose.SCRAP : row.isSupplementalPackaging()
                ? QuotationPriceSession.Purpose.PACKAGE : QuotationPriceSession.Purpose.MATERIAL));
      }
    }
    var read = session.read(inputs.values());
    for (var entry : inputs.entrySet()) {
      var item = entry.getKey();
      var input = entry.getValue();
      var value = read.values().get(input);
      if (value == null) {
        var problem = read.problems().get(input);
        results.put(item, NormalMaterialPricePrepareResult.gap(STATUS_MISSING_PRICE, GAP_TYPE_MISSING_PRICE,
            problem.reason().name(), MonthlyPreparationPrice.KIND, "lp_material_monthly_price", problem.message()));
        continue;
      }
      var quantity = quantity(item);
      String path = MonthlyBomPricingScope.pathKey(item.getBomRow().getPath());
      session.consume(path, input, quantity.abs());
      BigDecimal price = scenario.scenarioType() == QuotePriceScenarioType.FINANCE_QUOTE_BASE
          ? value.financialPrice() : value.customerPrice();
      var result = NormalMaterialPricePrepareResult.ready(price, price.multiply(quantity),
          value.selection().fact().type() == MonthlyAdoptionPlan.PricingType.HOUSEHOLD_INQUIRY ? "HOUSEHOLD_INQUIRY" : "月度物料价",
          MonthlyPreparationPrice.KIND, value.selection().monthlyPriceId(), "按当月适用物料价格准备");
      result.setPriceType(value.selection().fact().type().name());
      result.setInputSnapshotJson(monthlyCodec.encode(MonthlyPreparationPrice.of(value, path, quantity)));
      results.put(item, result);
    }
    return Map.copyOf(results);
  }

}
