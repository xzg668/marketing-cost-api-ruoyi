package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbCostExplanation.Price;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost.AdoptedPrice;
import com.sanhua.marketingcost.service.pricing.FactorPriceUnit;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceScenarios.Pair;
import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/** Formats captured formula inputs; never queries current sources or executes a price formula. */
final class JjbPriceCalculation {
  private final ObjectMapper json;
  JjbPriceCalculation(ObjectMapper json) {
    this.json = json.copy().findAndRegisterModules()
        .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  Price describe(AdoptedPrice adopted, Pair pair) {
    var family = adopted.request().family();
    var root = tree(adopted.selection().fact().evidence().json());
    var source = root.path("source");
    var selected = source.has("selected") ? source.path("selected") : source;
    var kind = root.path("sourceKind").asText();
    var details = JjbSourceEvidence.describe(root);
    var label = new ArrayList<String>();
    label.add(details.get("name"));
    for (String key : List.of("originalMonth", "file", "sheet", "row", "document"))
      if (details.containsKey(key)) label.add((key.equals("row") ? "第" : "") + details.get(key) + (key.equals("row") ? "行" : ""));
    if (selected.hasNonNull("supplierName")) label.add(selected.path("supplierName").asText());
    if (selected.hasNonNull("id")) label.add("来源编号 " + selected.path("id").asText());
    String sourceName = String.join(" · ", label);
    if (pair == null) return new Price(family.materialCode(), sourceName, "历史未记录", "历史未记录", "历史未记录计算依据");
    var calculation = source.hasNonNull("calculationJson") ? tree(source.path("calculationJson").asText())
        : source.has("calculation") ? source.path("calculation") : selected.path("calculation");
    var customer = tree(pair.customerEvidenceJson());
    var context = customerContext(customer, family.materialCode());
    var baseInputs = new LinkedHashMap<String, String>();
    var customerInputs = new LinkedHashMap<String, String>();
    calculation.path("inputs").fields().forEachRemaining(e -> baseInputs.put(e.getKey(), plain(e.getValue())));
    if (!kind.equals("LINKED_LEGACY") || pair.financialPrice().compareTo(pair.customerPrice()) == 0)
      customerInputs.putAll(baseInputs);
    var bases = new ArrayList<String>(); var customers = new ArrayList<String>();
    var bindings = calculation.path("factorBindings");
    if (bindings.isObject()) {
      bindings.fields().forEachRemaining(entry -> {
        var binding = entry.getValue();
        String key = binding.path("canonicalKey").asText();
        FactorPriceUnit unit = factorUnit(binding.path("unit").asText());
        String base = plain(calculation.path("inputs").path(entry.getKey()));
        if (base != null) baseInputs.put(entry.getKey(), base);
        String value = context != null ? number(context.requiredPrice(key, unit, true))
            : pair.financialPrice().compareTo(pair.customerPrice()) == 0 ? base : null;
        if (value != null) customerInputs.put(entry.getKey(), value); else customerInputs.remove(entry.getKey());
        bases.add(factorName(key) + " " + Objects.toString(base, "历史未记录") + " " + unitName(unit));
        customers.add(factorName(key) + " " + Objects.toString(value, "历史未记录") + " " + unitName(unit));
      });
    }
    // Inquiry formulas also capture their exact named inputs and factor mapping.
    if (bindings.isMissingNode() && calculation.path("factors").isObject()) {
      calculation.path("inputs").fields().forEachRemaining(e -> {baseInputs.put(e.getKey(), plain(e.getValue())); customerInputs.put(e.getKey(), plain(e.getValue()));});
      calculation.path("factors").fields().forEachRemaining(e -> {
        String key = e.getValue().asText();
        var unit = factorUnit(calculation.path("financeFactors").path(key).path("unit").asText());
        String base = baseInputs.get(e.getKey());
        String value = context == null ? pair.financialPrice().compareTo(pair.customerPrice()) == 0 ? base : null
            : number(context.requiredPrice(key, unit, true));
        if (value == null) customerInputs.remove(e.getKey()); else customerInputs.put(e.getKey(), value);
        bases.add(factorName(key) + " " + base + " " + unitName(unit));
        customers.add(factorName(key) + " " + Objects.toString(value, "历史未记录") + " " + unitName(unit));
      });
    }
    if (kind.equals("LINKED_LEGACY")) {
      // Old variable formulas saved P0 inputs, but did not always save substituted P1 inputs.
      baseInputs.forEach((key, value) -> bases.add(key + "=" + value));
      if (context != null) calculation.path("factorKeys").fields().forEachRemaining(entry -> {
        String key = entry.getValue().asText();
        var saved = context.financial().factors().get(key);
        if (saved != null) customers.add(factorName(key) + " "
            + number(context.requiredPrice(key, factorUnit(saved.unit()), true)) + " " + unitName(factorUnit(saved.unit())));
      });
    }
    var household = customer.path("householdCalculation");
    String expression = calculation.path("expression").asText(selected.path("formulaExpr").asText(""));
    String formula;
    if (household.isObject()) {
      var changes = new ArrayList<String>();
      for (String metal : List.of("Copper", "Zinc")) {
        String field = metal.equals("Copper") ? "copperGrams" : "zincGrams";
        String weight = plain(household.path(field));
        if (weight != null && new BigDecimal(weight).signum() == 0) continue;
        String name = metal.equals("Copper") ? "铜" : "锌";
        String base = Objects.toString(plain(household.path("base"+metal)), "历史未记录");
        String quoted = Objects.toString(plain(household.path("customer"+metal)), "历史未记录");
        bases.add(name + " " + base + " 元/公斤");
        customers.add(name + " " + quoted + " 元/公斤");
        changes.add("("+quoted+"−"+base+")×"+Objects.toString(weight,"历史未记录")+"/1000");
      }
      if (changes.isEmpty()) {
        bases.add("铜、锌重量为0，不参与联动");
        customers.add("沿用基准询价");
        formula = "基准询价="+number(pair.financialPrice())+"；客户询价="+number(pair.customerPrice());
      } else {
        formula = "基准询价="+number(pair.financialPrice())+"\n客户询价："+number(pair.financialPrice())
            +"+("+String.join("+",changes)+")/1.13="+number(pair.customerPrice());
      }
    } else if (!expression.isBlank()) {
      boolean tax = calculation.path("sourceTaxIncluded").asBoolean(calculation.path("taxIncluded").asBoolean(false));
      formula = "基准：" + substitute(expression, baseInputs, tax) + "=" + number(pair.financialPrice())
          + "\n客户：" + (kind.equals("LINKED_LEGACY") && customerInputs.isEmpty()
              ? "原版本未记录完整代入值；保存单价=" + number(pair.customerPrice())
              : substitute(expression, customerInputs, tax) + "=" + number(pair.customerPrice()));
    } else {
      if (calculation.hasNonNull("factorKey")) {
        String key=calculation.path("factorKey").asText(); var unit=factorUnit(calculation.path("factorUnit").asText());
        bases.add(factorName(key)+" "+Objects.toString(plain(calculation.path("result").path("factorValue")), "历史未记录")+" "+unitName(unit));
        customers.add(factorName(key)+" "+(context==null?"历史未记录":number(context.requiredPrice(key,unit,true)))+" "+unitName(unit));
      }
      formula = (kind.contains("RANGE") ? "按各自行情在保存区间规则中取价\n" : "保存的来源单价\n")
          + "基准：" + number(pair.financialPrice()) + "；客户：" + number(pair.customerPrice());
    }
    return new Price(family.materialCode(), sourceName, bases.isEmpty() ? "不适用／原版本未记录因素" : String.join("\n", bases),
        customers.isEmpty() ? "不适用／原版本未记录因素" : String.join("\n", customers), formula + "（" + ("CNY".equals(family.currency()) ? "元" : family.currency()) + "/" + family.unit() + "，" + (family.taxBasis().name().equals("EXCLUDED") ? "不含税" : "含税") + "）");
  }

  private PriceFactorContext customerContext(JsonNode node, String material) {
    for (var context : node.path("customerFactorContexts"))
      if (material.equals(context.path("materialCode").asText())) return json.convertValue(context, PriceFactorContext.class);
    return null;
  }
  static String substitute(String expression, Map<String,String> inputs, boolean taxIncluded) {
    var matcher = Pattern.compile("\\[([^]\\r\\n]+)]").matcher(expression);
    var result = new StringBuffer();
    while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(
        inputs.containsKey(matcher.group(1)) ? "("+inputs.get(matcher.group(1))+")" : matcher.group()));
    matcher.appendTail(result);
    return taxIncluded ? "("+result+")/1.13" : result.toString();
  }
  private JsonNode tree(String value) {
    if (value == null || value.isBlank()) return json.createObjectNode();
    try { return json.readTree(value); }
    catch (java.io.IOException error) { throw new IllegalStateException("保存的价格说明无法读取", error); }
  }
  private static String plain(JsonNode value) {
    if (value.isMissingNode() || value.isNull()) return null;
    return number(new BigDecimal(value.asText()));
  }
  private static FactorPriceUnit factorUnit(String value) {
    return value.startsWith("CNY_") ? FactorPriceUnit.valueOf(value) : FactorPriceUnit.require(value);
  }
  private static String unitName(FactorPriceUnit value) { return switch(value) {case CNY_G -> "元/克";case CNY_KG -> "元/公斤";case CNY_TON -> "元/吨";}; }
  private static String factorName(String key) {
    return switch(key) {case "AVG|1#CU" -> "铜";case "AVG|1#ZN" -> "锌";case "AVG|1#AG" -> "白银";case "AVG|1#SN" -> "锡";default -> key.substring(key.lastIndexOf('|')+1);};
  }
  static String number(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
