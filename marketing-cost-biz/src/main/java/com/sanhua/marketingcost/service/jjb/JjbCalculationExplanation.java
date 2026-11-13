package com.sanhua.marketingcost.service.jjb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbCostExplanation;
import com.sanhua.marketingcost.dto.jjb.JjbCostExplanation.*;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Snapshot;
import com.sanhua.marketingcost.service.costing.MonthlyProductCost;
import com.sanhua.marketingcost.service.costing.SeparatePartCost;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Builds a readable reconciliation from immutable cost inputs and their captured price evidence. */
final class JjbCalculationExplanation {
  private final JjbPriceCalculation prices;
  JjbCalculationExplanation(ObjectMapper json) { prices = new JjbPriceCalculation(json); }

  JjbCostExplanation describe(Snapshot snapshot, List<Row> rows) {
    if (snapshot == null || snapshot.calculation() == null) return null;
    var calc = snapshot.calculation(); var input = calc.ordinaryInputs();
    var notes = new LinkedHashMap<Long, Price>();
    for (var adopted : calc.adoptedPrices()) {
      long id = adopted.selection().monthlyPriceId();
      if (!notes.containsKey(id)) notes.put(id, prices.describe(adopted, snapshot.priceScenarios().stream()
          .filter(pair -> pair.monthlyPriceId() == id).findFirst().orElse(null)));
    }
    var materials = new ArrayList<Material>();
    var order = new IdentityHashMap<Material, Integer>();
    BigDecimal separateTotal = BigDecimal.ZERO;
    for (var line : input.materials()) {
      if (line.explanationOf() != null) continue;
      var row = rows.stream().filter(r -> r.paths().contains(line.path()) && "PART".equals(r.kind()))
          .findFirst().orElse(null);
      BigDecimal financial = line.financeAmount(), customer = line.customerAmount();
      String formula = "基准金额 " + n(financial) + "；客户金额 " + n(customer);
      var direct = calc.adoptedPrices().stream().filter(a -> line.monthlyPriceIds().size() == 1
          && line.monthlyPriceIds().contains(a.selection().monthlyPriceId())
          && line.materialCode().equals(a.request().family().materialCode())).findFirst().orElse(null);
      if (direct != null) {
        var pair = snapshot.priceScenarios().stream().filter(p -> p.monthlyPriceId() == direct.selection().monthlyPriceId()).findFirst().orElse(null);
        if (pair != null && roundedEqual(pair.financialPrice().multiply(line.quantity()), financial)
            && roundedEqual(pair.customerPrice().multiply(line.quantity()), customer))
          formula = "基准：" + n(pair.financialPrice()) + "×" + n(line.quantity()) + "=" + n(financial)
              + "\n客户：" + n(pair.customerPrice()) + "×" + n(line.quantity()) + "=" + n(customer);
      } else if (row != null && !row.manufacturing().isEmpty()) {
        formula = row.manufacturing().stream().map(m -> Objects.toString(m.get("substitution"), ""))
            .filter(s -> !s.isBlank()).collect(java.util.stream.Collectors.joining("；"))
            + "\n本项用量 " + n(line.quantity()) + "；" + formula;
      }
      if (line.separateRule() != null) {
        financial = SeparatePartCost.amount(line, input, false);
        customer = SeparatePartCost.amount(line, input, true);
        String suffix = "×(1+"+n(input.lossRate())+")÷(1−"+n(line.separateRule().manufactureRate())+")×(1+"
            + n(input.managementRate().add(input.salesRate()).add(input.financeRate()))+")";
        formula += "\n单独计费：基准 " + n(line.financeAmount()) + suffix + "=" + n(financial)
            + "；客户 " + n(line.customerAmount()) + suffix + "=" + n(customer);
        separateTotal = separateTotal.add(financial);
      }
      formula += "\n差异：" + n(customer) + "−(" + n(financial) + ")=" + n(customer.subtract(financial));
      String unit = row == null ? null : row.unit();
      if (unit == null && direct != null) unit = direct.request().family().unit();
      materials.add(new Material(line.materialCode(), row == null ? line.materialCode() : row.name(),
          n(line.quantity()), unit, line.monthlyPriceIds().stream().map(notes::get).filter(Objects::nonNull).toList(),
          formula, n(financial), n(customer), n(customer.subtract(financial))));
      order.put(materials.getLast(), row == null ? Integer.MAX_VALUE : rows.indexOf(row));
    }
    for (var line : calc.household()) {
      var row = rows.stream().filter(r -> "HOUSEHOLD".equals(r.kind()) && r.monthlyPriceIds().contains(line.monthlyPriceId())).findFirst().orElse(null);
      String factor = "÷(1+" + n(line.householdRate()) + ")×(1+" + n(line.householdRate()) + "+" + n(line.salesRate()) + ")×" + n(line.quantity());
      var price = notes.get(line.monthlyPriceId());
      materials.add(new Material(line.materialCode(), row == null ? line.materialCode() : row.name(), n(line.quantity()), line.pricingUnit(),
          price == null ? List.of() : List.of(price),
          "基准："+n(line.price())+factor+"="+n(line.amount())+"\n客户："+n(line.effectiveCustomerPrice())+factor+"="+n(line.effectiveCustomerAmount())
              +"\n差异："+n(line.effectiveCustomerAmount())+"−("+n(line.amount())+")="+n(line.effectiveCustomerAmount().subtract(line.amount())),
          n(line.amount()), n(line.effectiveCustomerAmount()), n(line.effectiveCustomerAmount().subtract(line.amount()))));
      order.put(materials.getLast(), row == null ? Integer.MAX_VALUE : rows.indexOf(row));
    }
    materials.sort(Comparator.comparingInt(order::get));
    var terms = new ArrayList<Term>();
    var ordinary = calc.ordinary();
    terms.add(new Term("属性调整后制造成本",
        "材料 " + n(calc.financeMaterialCost()) + "＋直接人工 " + n(input.directLabor()) + "＋间接人工 " + n(input.indirectLabor()) + "＝" + n(ordinary.lossBase())
            + "\n净损失：" + n(ordinary.lossBase()) + "×" + n(input.lossRate()) + "＝" + n(ordinary.loss())
            + "\n制造成本：( " + n(ordinary.lossBase()) + "＋" + n(ordinary.loss()) + ")÷(1−" + n(input.manufactureRate()) + ")＝" + n(ordinary.manufactureCost())
            + "\n属性调整：" + n(ordinary.manufactureCost()) + "×" + n(input.coefficient()), n(ordinary.adjustedManufactureCost())));
    terms.add(new Term("管理费用", n(ordinary.adjustedManufactureCost()) + "×" + n(input.managementRate()), n(ordinary.management())));
    terms.add(new Term("营业费用", n(ordinary.adjustedManufactureCost()) + "×" + n(input.salesRate()), n(ordinary.sales())));
    terms.add(new Term("财务费用", n(ordinary.adjustedManufactureCost()) + "×" + n(input.financeRate()), n(ordinary.finance())));
    terms.add(new Term("其他独立费用", "本版本保存金额", n(input.otherIndependentFees())));
    terms.add(new Term("单独计费零件合计", "单独计费零件的基准金额相加", n(separateTotal)));
    terms.add(new Term("家用部件计入金额", n(calc.householdSubtotal()) + "×" + n(input.coefficient()), n(calc.householdContribution())));
    for (var fee : calc.fees()) if (fee.amount() != null) terms.add(new Term(fee.code().displayName(), "本版本保存金额", n(fee.amount())));
    BigDecimal total = terms.stream().map(t -> new BigDecimal(t.amount())).reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal difference = materials.stream().map(m -> new BigDecimal(m.difference())).reduce(BigDecimal.ZERO, BigDecimal::add);
    String historical = !roundedEqual(total, calc.totalCost()) || !roundedEqual(difference, calc.cuMaterialAdjustment())
        ? "历史明细与保存汇总口径不同，原总额保留，请按原版本核对。" : "";
    return new JjbCostExplanation(terms, materials,
        historical + "金额单位：元/件。基价、客户价按各自标明的单位；差异按未舍入明细加总后保留6位。数据来自本成本版本保存的依据。");
  }
  private static boolean roundedEqual(BigDecimal a, BigDecimal b) { return a.setScale(6,RoundingMode.HALF_UP).compareTo(b.setScale(6,RoundingMode.HALF_UP)) == 0; }
  private static String n(BigDecimal value) { return JjbPriceCalculation.number(value); }
}
