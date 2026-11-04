package com.sanhua.marketingcost.service.pricing.folder;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** 文件夹月度刷新必须使用目标月发布；不能沿用上月基价伪装本月完成。 */
@Component
public class MonthlyPriceFolderFinance {
  private final FinanceFactorPriceReader finance;
  private final OriginalLinkedRuleCalculator linked;
  private final OriginalRangeRuleCalculator ranges;
  public MonthlyPriceFolderFinance(FinanceFactorPriceReader finance, OriginalLinkedRuleCalculator linked,
      OriginalRangeRuleCalculator ranges) { this.finance = finance; this.linked = linked; this.ranges = ranges; }

  public List<PriceFactorContext> contexts(MaterialPriceRuleKey key, String month) {
    var conditions = key.orgCode().equals("220")
        ? List.of(FactorPriceScope.Condition.NON_TENDER, FactorPriceScope.Condition.TENDER)
        : List.of(FactorPriceScope.Condition.GENERAL);
    List<PriceFactorContext> result = new ArrayList<>();
    for (var condition : conditions) {
      var snapshot = finance.read(new FinanceFactorImportContext(key.businessUnitType(), key.orgCode(), month, null, null), condition, null);
      var context = new PriceFactorContext(key.materialCode(), new PriceMonthSelection(month, month), snapshot, null);
      if (key.orgCode().equals("220")) context = context.withBoardInputs(new BoardPriceInputs(null, null,
          condition == FactorPriceScope.Condition.TENDER));
      result.add(context);
    }
    return result;
  }
  public void validate(OriginalLinkedWorkbookParser.Rule rule, String month) {
    for (var context : contexts(rule.identity(), month)) linked.calculate(rule, context);
  }
  public void validate(OriginalRangeWorkbookParser.Rule rule, String month) {
    for (var context : contexts(rule.identity(), month)) ranges.calculate(rule, context);
  }

  /** 一次处理持有同一财务目录锁，按组织/月份只读取一份基价，避免逐料号重复查表。 */
  public Validation validation() { return new Validation(); }
  public final class Validation {
    private final Map<String, List<PriceFactorContext>> snapshots = new HashMap<>();
    private List<PriceFactorContext> forMaterial(MaterialPriceRuleKey key, String month) {
      return snapshots.computeIfAbsent(key.businessUnitType() + ":" + key.orgCode() + ":" + month,
          ignored -> contexts(key, month)).stream().map(context -> {
            var current = new PriceFactorContext(key.materialCode(), context.months(), context.financial(), null);
            return key.orgCode().equals("220") ? current.withBoardInputs(context.boardInputs()) : current;
          }).toList();
    }
    public void validate(OriginalLinkedWorkbookParser.Rule rule, String month) {
      for (var context : forMaterial(rule.identity(), month)) linked.calculate(rule, context);
    }
    public void validate(OriginalRangeWorkbookParser.Rule rule, String month) {
      for (var context : forMaterial(rule.identity(), month)) ranges.calculate(rule, context);
    }
  }
}
