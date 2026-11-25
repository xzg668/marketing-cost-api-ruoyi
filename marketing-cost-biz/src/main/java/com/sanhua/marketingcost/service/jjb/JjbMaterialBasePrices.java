package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceScenarios.Pair;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Header market prices use the captured customer's Cu band, independently of formula substitution. */
final class JjbMaterialBasePrices {
  private final ObjectMapper json;
  JjbMaterialBasePrices(ObjectMapper json) {
    this.json = json.copy().findAndRegisterModules();
  }

  private static final Map<String, String> FACTOR_FIELDS = Map.of(
      "AVG|1#CU", "copperPrice", "AVG|1#ZN", "zincPrice",
      "AVG|美国柜装黄铜", "brassPrice", "AVG|H65黄铜边料", "brassScrapPrice",
      "AVG|1#AG", "silverPrice");
  private static final Map<String, String> CUSTOMER_FIELDS = Map.of(
      "copper", "copperPrice", "zinc", "zincPrice", "silver", "silverPrice",
      "sus304", "sus304Price", "sus316", "sus316lPrice");
  private static final Map<String, String> COPPER_BAND_FIELDS = Map.of(
      PriceFactorContext.COPPER, "copperPrice", PriceFactorContext.ZINC, "zincPrice",
      "AVG|美国柜装黄铜", "brassPrice", "AVG|H65黄铜边料", "brassScrapPrice");

  void apply(Map<String, String> header, List<Pair> scenarios, List<String> warnings) {
    var values = new LinkedHashMap<String, Set<BigDecimal>>();
    var customers = new LinkedHashMap<String, Set<BigDecimal>>();
    var marketValues = new LinkedHashMap<String, Set<BigDecimal>>();
    boolean capturedCustomer = false;
    boolean customerBandAvailable = false;
    for (var pair : scenarios) {
      JsonNode evidence = read(pair.customerEvidenceJson());
      var customer = evidence.path("customerMaterialPrices");
      if (customer.isObject()) {
        capturedCustomer = true;
        if (customer.has("priceLinkMode")) header.put("priceLinkMode", customer.path("priceLinkMode").asText(""));
        CUSTOMER_FIELDS.forEach((field, name) -> {
          if (customer.hasNonNull(field)) add(customers, name, new BigDecimal(customer.path(field).asText()));
        });
      }
      for (var saved : evidence.path("customerFactorContexts")) {
        var context = json.convertValue(saved, PriceFactorContext.class);
        BigDecimal copper = customer.hasNonNull("copper") ? new BigDecimal(customer.path("copper").asText())
            : context.customerMaterialPrices() != null ? context.customerMaterialPrices().copper()
            : context.customerCopperPricePerTon();
        // The header describes the customer's market band even for fixed sales.
        // Do not use copperForBand()/requiredPrice(): those belong to the saved calculation policy.
        var marketBand = context.financial().customerBand(copper);
        if (marketBand != null) {
          customerBandAvailable = true;
          COPPER_BAND_FIELDS.forEach((key, field) -> {
            var factor = marketBand.factors().get(key);
            if (factor == null || factor.price() == null || !factor.taxIncluded()) return;
            var unit = field.equals("brassPrice") || field.equals("brassScrapPrice")
                ? FactorPriceUnit.CNY_KG : FactorPriceUnit.CNY_TON;
            add(marketValues, field, FactorPriceUnit.require(factor.unit()).convert(factor.price(), unit));
          });
        }
        var keys = new LinkedHashSet<>(context.financial().factors().keySet());
        var financialBand = context.financial().cuBands().stream()
            .filter(band -> band.schemeCode().equals(context.financial().scope().schemeCode())).findFirst().orElse(null);
        if (financialBand != null) financialBand.factors().keySet().stream()
            .filter(CustomerMaterialPrices::usesCopperBand).forEach(keys::add);
        if (context.customerBand() != null) context.customerBand().factors().keySet().stream()
            .filter(CustomerMaterialPrices::usesCopperBand).forEach(keys::add);
        for (String key : keys) {
          String canonical = CustomerMaterialPrices.canonicalKey(key);
          String field = FACTOR_FIELDS.get(canonical);
          var stainless = BoardStainlessGrade.fromFactorKey(canonical);
          if (stainless != null) field = stainless == BoardStainlessGrade.SUS304 ? "sus304Price" : "sus316lPrice";
          if (field == null) continue;
          if (marketBand != null && CustomerMaterialPrices.usesCopperBand(canonical)) continue;
          boolean byBand = "FINANCE_CU_BAND".equals(context.inputSource(canonical));
          var factor = byBand ? context.customerBand().factors().get(canonical) : context.financial().factors().get(canonical);
          if (factor == null && !byBand && financialBand != null) factor = financialBand.factors().get(canonical);
          // An unused, absent factor must neither block this sheet nor become an invented zero.
          if (factor == null || factor.price() == null || !factor.taxIncluded()) continue;
          var unit = field.equals("brassPrice") || field.equals("brassScrapPrice")
              ? FactorPriceUnit.CNY_KG : FactorPriceUnit.CNY_TON;
          BigDecimal value = context.financial().factors().containsKey(canonical) || byBand
              ? context.requiredPrice(canonical, unit, true)
              : FactorPriceUnit.require(factor.unit()).convert(factor.price(), unit);
          add(values, field, value);
        }
      }
    }
    if (capturedCustomer) {
      CUSTOMER_FIELDS.values().forEach(header::remove);
      header.remove("customerCuPrice");
      write(header, customers, warnings);
      var copper = customers.get("copperPrice");
      if (copper != null && copper.size() == 1)
        header.put("customerCuPrice", money(copper.iterator().next().movePointLeft(3)));
    }
    if (customerBandAvailable) {
      // Missing values in the selected band stay blank instead of mixing in baseline or demand Zn.
      COPPER_BAND_FIELDS.values().forEach(field -> { header.remove(field); values.remove(field); });
    }
    write(header, values, warnings);
    write(header, marketValues, warnings);
  }

  private static void add(Map<String, Set<BigDecimal>> values, String key, BigDecimal value) {
    values.computeIfAbsent(key, ignored -> new TreeSet<>(BigDecimal::compareTo)).add(value);
  }
  private static void write(Map<String, String> header, Map<String, Set<BigDecimal>> values, List<String> warnings) {
    values.forEach((key, prices) -> {
      if (prices.size() == 1) header.put(key, money(prices.iterator().next()));
      else {
        header.remove(key);
        warnings.add("本版本" + key + "采用了多份不同基价，表头留空，请查看各物料保存的代入依据");
      }
    });
  }
  private static String money(BigDecimal value) { return value.setScale(6, RoundingMode.HALF_UP).toPlainString(); }
  private JsonNode read(String source) {
    try { return json.readTree(source); }
    catch (java.io.IOException error) { throw new IllegalArgumentException("保存的材料基价依据无法读取", error); }
  }
}
