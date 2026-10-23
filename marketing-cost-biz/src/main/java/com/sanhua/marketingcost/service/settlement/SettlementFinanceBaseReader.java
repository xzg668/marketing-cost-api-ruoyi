package com.sanhua.marketingcost.service.settlement;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.FactorPriceScope.Condition;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementScope;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** 结算两组织共用商用默认方案；没有独立基价、客户Cu覆盖或板换方案回退。 */
@Service
public class SettlementFinanceBaseReader {
  private final FinanceFactorPriceReader finance;

  public SettlementFinanceBaseReader(FinanceFactorPriceReader finance) { this.finance = finance; }

  public java.util.SortedSet<String> publishedMonths(String unit) { return finance.publishedMonths(unit, "210"); }

  public Optional<SettlementBasePrice> effective(SettlementScope scope) {
    try {
      var context = new FinanceFactorImportContext(scope.businessUnitType(), "210", scope.priceMonth(), null, null);
      return Optional.of(SettlementBasePrice.fromFinancial(
          finance.readEffective(context, Condition.GENERAL, "SETTLEMENT").financial()));
    } catch (FinanceFactorPriceReader.MissingPublicationException missing) {
      return Optional.empty();
    }
  }
}
