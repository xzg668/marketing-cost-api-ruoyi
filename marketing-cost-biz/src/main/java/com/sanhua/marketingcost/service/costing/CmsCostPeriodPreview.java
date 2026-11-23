package com.sanhua.marketingcost.service.costing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class CmsCostPeriodPreview {
  private static final Set<String> DEPARTMENT_CODES = Set.of("WATER_POWER", "OVERHAUL", "TOOLING_REPAIR", "DEPT_OTHER");

  record Amount(String code, String label, String saved, String proposed) {}
  record Result(List<Amount> amounts, String unavailableFee) {}

  static Result calculate(MonthlyProductCost.Result saved, String code, String label, BigDecimal proposed,
      BigDecimal savedAuxiliary) {
    var inputs = saved.ordinaryInputs();
    var original = saved.ordinary();
    var direct = CmsCostPeriodService.DIRECT.equals(code) ? proposed : inputs.directLabor();
    var indirect = CmsCostPeriodService.INDIRECT.equals(code) ? proposed : inputs.indirectLabor();
    var labor = direct.add(indirect);
    var material = saved.financeMaterialCost();
    BigDecimal originalExpense = savedAuxiliary == null
        ? (CmsCostPeriodService.DIRECT.equals(code) ? inputs.directLabor() : inputs.indirectLabor()) : savedAuxiliary;
    if (savedAuxiliary != null) material = material.add(proposed.subtract(savedAuxiliary));
    var amounts = new ArrayList<Amount>();
    var selected = row(code, label, originalExpense, proposed);
    amounts.add(selected);
    for (var fee : inputs.details()) {
      if (savedAuxiliary != null || !DEPARTMENT_CODES.contains(fee.costCode()) || fee.amount() == null) continue;
      var parameters = fee.composition() == null ? null : fee.composition().rows().stream()
          .filter(value -> "lp_department_fund_rate".equals(value.get("sourceTable")))
          .findFirst().orElse(null);
      String wageRate = parameters == null ? null : parameters.get("manhourRate");
      if (wageRate == null || wageRate.isBlank() || fee.rate() == null) {
        return new Result(List.of(selected), fee.costName());
      }
      var department = OrdinaryCostAmounts.department(labor, new BigDecimal(wageRate), fee.rate()).amount();
      material = material.add(department.subtract(fee.amount()));
      amounts.add(row(fee.costCode(), fee.costName(), fee.amount(), department));
    }
    var updated = OrdinaryCostAmounts.calculate(material, direct, indirect, inputs.lossRate(),
        inputs.manufactureRate(), inputs.coefficient(), inputs.managementRate(), inputs.salesRate(),
        inputs.financeRate(), inputs.otherIndependentFees());
    amounts.add(row("MATERIAL", "材料费", saved.financeMaterialCost(), material));
    amounts.add(row("LOSS", "净损失", original.loss(), updated.loss()));
    amounts.add(row("MANUFACTURE", "制造费用", original.manufactureFee(), updated.manufactureFee()));
    amounts.add(row("MGMT_EXP", "管理费用", original.management(), updated.management()));
    amounts.add(row("SALES_EXP", "营业费用", original.sales(), updated.sales()));
    amounts.add(row("FIN_EXP", "财务费用", original.finance(), updated.finance()));
    // The selected wage changes the ordinary cost. Saved independent components and the material difference retain their basis.
    var delta = updated.total().subtract(original.total());
    amounts.add(row("TOTAL", "总成本 1", saved.totalCost(), saved.totalCost().add(delta)));
    amounts.add(row("FINAL", "总成本 2", saved.finalQuoteAmount(), saved.finalQuoteAmount().add(delta)));
    return new Result(List.copyOf(amounts), null);
  }

  private static Amount row(String code, String label, BigDecimal before, BigDecimal after) {
    return new Amount(code, label, CmsCostCompositions.number(before), CmsCostCompositions.number(after));
  }
}
