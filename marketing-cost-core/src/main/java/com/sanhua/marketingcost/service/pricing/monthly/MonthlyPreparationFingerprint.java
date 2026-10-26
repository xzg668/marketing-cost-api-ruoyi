package com.sanhua.marketingcost.service.pricing.monthly;

import java.util.ArrayList;
import java.util.List;

/** Deterministic across API/worker processes, including unordered applicability/dependency sets. */
public final class MonthlyPreparationFingerprint {
  private MonthlyPreparationFingerprint() {}

  public static String of(MonthlyPreparationInputs inputs) {
    var fields = new ArrayList<String>();
    fields.add(inputs.rootPath());
    var prices = new ArrayList<String>();
    for (var price : inputs.prices()) {
      var selected = price.selection();
      var fact = selected.fact();
      prices.add(MonthlyPriceCanonical.fields("PRICE", request(price.request()),
          String.valueOf(selected.monthlyPriceId()), selected.applicability().canonical(), fact.type().name(),
          MonthlyPriceCanonical.number(fact.price()), MonthlyPriceCanonical.number(fact.householdRate()),
          fact.evidence().sha256(), MonthlyPriceCanonical.fields("DEPENDENCIES",
              fact.dependencies().stream().map(MonthlyPreparationFingerprint::request).sorted().toArray(String[]::new))));
    }
    fields.add(MonthlyPriceCanonical.fields("PRICES", prices.stream().sorted().toArray(String[]::new)));
    fields.add(MonthlyPriceCanonical.fields("USES", inputs.uses().stream().map(use ->
        MonthlyPriceCanonical.fields("USE", use.path(), request(use.request()),
            MonthlyPriceCanonical.number(use.cumulativeQuantity()))).sorted().toArray(String[]::new)));
    fields.add(MonthlyPriceCanonical.fields("SUPPRESSED", inputs.suppressedPaths().stream().sorted().toArray(String[]::new)));
    return MonthlyPriceDocumentCodec.sha256(MonthlyPriceCanonical.fields("MONTHLY_PREPARATION_V1", fields.toArray(String[]::new)));
  }

  private static String request(MonthlyPriceRequest request) {
    return MonthlyPriceCanonical.fields("REQUEST", request.family().canonical(), request.priceOrgCode(),
        ConditionDomain.exact(request.conditions()).canonical(), MonthlyPriceCanonical.number(request.priceBasisQuantity()));
  }
}
