package com.sanhua.marketingcost.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.stereotype.Component;

/** Financial wording and substituted formulas from the same immutable inputs used by the calculators. */
@Component
public final class MakePartPriceTraceSource {
  public record Factor(String name, String unit, String original, String trial) {}
  public record Source(String materialNo, String materialName, String unit, String formula,
      String originalCalculation, String trialCalculation, String originalPrice, String trialPrice,
      boolean copperLinked, String financeCopperPerTon, List<Factor> factors, String supplier,
      String file, String location) {}
  private record Binding(String variable, String key, FactorPriceUnit unit) {}
  private final MonthlyPriceDocumentCodec codec;

  public MakePartPriceTraceSource(MonthlyPriceDocumentCodec codec) { this.codec = codec; }

  public Source noScrap() {
    return new Source(null, "已确认无废料", "元/千克", "不扣废料", "0", "0", "0", "0", false,
        null, List.of(), null, null, null);
  }

  public Source describe(String code, String name, MonthlyPreparationPrice price,
      MonthlyPriceScenarios.Pair projection, BigDecimal originalPrice, BigDecimal trialPrice, String adoptedUnit) {
    var root = read(price.selection().fact().evidence().json());
    var source = root.path("source");
    String kind = root.path("sourceKind").asText();
    var bindings = new ArrayList<Binding>();
    JsonNode contextNode = null;
    String expression = null, formula = null, location = null, file = null, supplier = null;
    boolean divideTax = false;
    var originalVariables = new LinkedHashMap<String, BigDecimal>();
    switch (kind) {
      case "LINKED" -> {
        var calculation = read(source.path("calculationJson").asText());
        var saved = read(source.path("selectedPublication").path("calculationJson").asText());
        var rule = saved.path("linkedRule");
        expression = rule.path("compiled").path("expression").asText();
        formula = rule.path("sourceFormulaText").asText(null);
        divideTax = rule.path("taxIncluded").asBoolean();
        contextNode = calculation.path("context");
        rule.path("factors").fields().forEachRemaining(e -> bindings.add(new Binding(e.getKey(),
            e.getValue().path("canonicalKey").asText(), FactorPriceUnit.valueOf(e.getValue().path("unit").asText()))));
        supplier = rule.path("identity").path("supplierName").asText(null);
        location = rule.path("sheet").asText() + "!" + rule.path("cell").asText();
      }
      case "SCRAP_PRICE" -> {
        var selected = source.path("selected");
        JsonNode input = null;
        for (var candidate : source.path("candidates"))
          if (candidate.path("original").path("id").equals(selected.path("id"))) input = candidate.path("input");
        if (input == null) throw new IllegalArgumentException("废料缺少已保存的计算依据");
        var rule = input.path("rule");
        expression = rule.path("expression").asText(null);
        formula = rule.path("sourceFormula").asText(null);
        contextNode = input.path("factors");
        rule.path("factorBindings").fields().forEachRemaining(e -> bindings.add(new Binding(e.getKey(),
            e.getValue().asText(), FactorPriceUnit.CNY_KG)));
        file = selected.path("sourceFileName").asText(null);
        supplier = selected.path("supplierName").asText(null);
      }
      case "RANGE_FACTOR" -> {
        var calculation = read(source.path("calculationJson").asText());
        contextNode = calculation.path("context");
        var saved = read(source.path("selected").path("sourceInputSnapshotJson").asText());
        var rule = saved.path("sources").path(0);
        bindings.add(new Binding("行情", rule.path("factorKey").asText(), FactorPriceUnit.valueOf(rule.path("factorUnit").asText())));
        formula = "按行情区间取价";
        file = source.path("selected").path("sourceFile").asText(null);
        supplier = rule.path("identity").path("supplierName").asText(null);
        location = rule.path("matrixSheet").asText() + " 第" + rule.path("matrixRow").asText() + "行";
      }
      case "INQUIRY" -> {
        var calculation = source.path("selected").path("calculation");
        if ("联动价".equals(calculation.path("priceType").asText())) {
          expression = calculation.path("expression").asText();
          formula = expression;
          calculation.path("inputs").fields().forEachRemaining(e -> originalVariables.put(e.getKey(), decimal(e.getValue())));
          calculation.path("factors").fields().forEachRemaining(e -> bindings.add(new Binding(e.getKey(),e.getValue().asText(),
              FactorPriceUnit.require(calculation.path("financeFactors").path(e.getValue().asText()).path("unit").asText()))));
          if (calculation.path("financial").isObject()) {
            var node = read("{}").deepCopy();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            object.put("materialCode", code);
            object.set("months", read("{\"requestedMonth\":\"" + price.request().family().month() + "\",\"sourceMonth\":\""
                + calculation.path("financial").path("scope").path("priceMonth").asText() + "\"}"));
            object.set("financial",calculation.path("financial"));
            contextNode = object;
          }
        }
      }
      default -> { /* Fixed/legacy source amounts are still replayed by MonthlyPriceScenarios. */ }
    }
    PriceFactorContext base = contextNode == null || !contextNode.isObject() ? null : convert(contextNode, PriceFactorContext.class).financialOnly();
    PriceFactorContext changed = base;
    var projected = read(projection.customerEvidenceJson());
    BigDecimal trialCu = projected.path("customerCopperPerTon").isNull() ? null
        : decimal(projected.path("customerCopperPerTon"));
    if (base != null && trialCu != null) {
      for (var candidate : projected.path("customerFactorContexts")) {
        if (code.equals(candidate.path("materialCode").asText())) { changed = convert(candidate, PriceFactorContext.class); break; }
      }
      changed = changed.withCustomerCopperPerTon(trialCu);
    }
    var trialVariables = new LinkedHashMap<>(originalVariables);
    var factors = new ArrayList<Factor>();
    boolean linked = false;
    for (var binding : bindings) {
      String factorName = binding.key();
      if (base != null && base.financial().factors().containsKey(binding.key()))
        factorName = base.financial().required(binding.key()).factorName();
      else if (PriceFactorContext.COPPER.equals(CustomerMaterialPrices.canonicalKey(binding.key()))) factorName = "Cu（铜价）";
      if (base != null) {
        originalVariables.put(binding.variable(), base.requiredPrice(binding.key(), binding.unit(), true));
        trialVariables.put(binding.variable(), changed.requiredPrice(binding.key(), binding.unit(), true));
      } else if (trialCu != null && PriceFactorContext.COPPER.equals(CustomerMaterialPrices.canonicalKey(binding.key()))) {
        trialVariables.put(binding.variable(), FactorPriceUnit.CNY_TON.convert(trialCu,binding.unit()));
      }
      boolean copper = PriceFactorContext.COPPER.equals(CustomerMaterialPrices.canonicalKey(binding.key()));
      linked |= copper || CustomerMaterialPrices.usesCopperBand(binding.key());
      factors.add(new Factor(copper ? "Cu（铜价）" : factorName, unit(binding.unit()),
          MakePartPriceTrialService.text(originalVariables.get(binding.variable())), MakePartPriceTrialService.text(trialVariables.get(binding.variable()))));
    }
    String financeCopper = base != null && base.financial().factors().containsKey(PriceFactorContext.COPPER)
        ? MakePartPriceTrialService.text(base.requiredPrice(PriceFactorContext.COPPER, FactorPriceUnit.CNY_TON, true)) : null;
    String originalCalculation = expression, trialCalculation = expression;
    if (expression != null && !expression.isBlank()) {
      for (var binding : bindings) {
        String label = factors.get(bindings.indexOf(binding)).name();
        expression = expression.replace("[" + binding.variable() + "]", label);
        if (originalVariables.get(binding.variable()) != null) originalCalculation = originalCalculation.replace("[" + binding.variable() + "]", MakePartPriceTrialService.text(originalVariables.get(binding.variable())));
        if (trialVariables.get(binding.variable()) != null) trialCalculation = trialCalculation.replace("[" + binding.variable() + "]", MakePartPriceTrialService.text(trialVariables.get(binding.variable())));
      }
      if (divideTax) { expression = "(" + expression + ")/1.13"; originalCalculation = "(" + originalCalculation + ")/1.13"; trialCalculation = "(" + trialCalculation + ")/1.13"; }
      if (price.unitPriceMultiplier().compareTo(BigDecimal.ONE) != 0) {
        String multiplier = "×" + MakePartPriceTrialService.text(price.unitPriceMultiplier());
        expression = "(" + expression + ")" + multiplier;
        originalCalculation = "(" + originalCalculation + ")" + multiplier;
        trialCalculation = "(" + trialCalculation + ")" + multiplier;
      }
      formula = pretty(expression);
      originalCalculation = pretty(originalCalculation);
      trialCalculation = pretty(trialCalculation);
    } else if ("RANGE_FACTOR".equals(kind) && base != null) {
      var rule = convert(read(source.path("selected").path("sourceInputSnapshotJson").asText()), OriginalRangeRuleSnapshot.SavedRule.class).rule();
      var calculator = new OriginalRangeRuleCalculator();
      originalCalculation = band(calculator.calculate(rule,base));
      trialCalculation = band(calculator.calculate(rule,changed));
    } else {
      formula = "固定单价";
      originalCalculation = MakePartPriceTrialService.text(originalPrice);
      trialCalculation = MakePartPriceTrialService.text(trialPrice);
      // Other supported price kinds can depend on Cu without a simple expression.
      if (originalPrice.compareTo(trialPrice) != 0) { linked = true; formula = "按原报价规则计算"; }
    }
    if (file == null && base != null) file = base.financial().fileName();
    return new Source(code, name, "元/" + adoptedUnit, formula, originalCalculation, trialCalculation,
        MakePartPriceTrialService.text(originalPrice), MakePartPriceTrialService.text(trialPrice), linked, financeCopper,
        List.copyOf(factors), supplier, file, location);
  }

  private static String band(OriginalRangeRuleCalculator.Result result) {
    var b = result.selectedBounds();
    return "行情 " + MakePartPriceTrialService.text(result.factorValue()) + "，取 "
        + (b.lowerInclusive() ? "[" : "(") + MakePartPriceTrialService.text(b.low()) + "～"
        + MakePartPriceTrialService.text(b.high()) + (b.upperInclusive() ? "]" : ")") + " 档";
  }
  private static String pretty(String expression) { return expression.replace("ROUND", "四舍五入").replace("*", "×").replace("/", "÷"); }
  private static String unit(FactorPriceUnit value) { return switch (value) { case CNY_TON -> "元/吨"; case CNY_KG -> "元/千克"; case CNY_G -> "元/克"; }; }
  private JsonNode read(String json) { return codec.decode(codec.wrap(json), JsonNode.class); }
  private <T> T convert(JsonNode json, Class<T> type) { return codec.decode(codec.wrap(json.toString()),type); }
  private static BigDecimal decimal(JsonNode node) { return new BigDecimal(node.asText()); }
}
