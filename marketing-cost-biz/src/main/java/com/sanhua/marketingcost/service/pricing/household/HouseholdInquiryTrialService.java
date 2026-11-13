package com.sanhua.marketingcost.service.pricing.household;

import com.sanhua.marketingcost.dto.FinanceFactorImportContext;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.CustomerMaterialPrices;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import com.sanhua.marketingcost.service.pricing.monthly.HouseholdInquiryStorage;
import com.sanhua.marketingcost.service.pricing.monthly.HouseholdMetalCalculation;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses the costing formula and effective financial publication without adopting a monthly price. */
@Service
@Transactional(readOnly = true)
public class HouseholdInquiryTrialService {
  private final HouseholdInquiryService inquiries;
  private final HouseholdInquiryStorage storage;
  private final FinanceFactorPriceReader finance;

  public HouseholdInquiryTrialService(HouseholdInquiryService inquiries, HouseholdInquiryStorage storage,
      FinanceFactorPriceReader finance) {
    this.inquiries = inquiries;
    this.storage = storage;
    this.finance = finance;
  }

  public record Request(String pricingMonth, BigDecimal customerCopperPerTon, BigDecimal customerZincPerTon) {}
  public record Factor(String name, String weightGrams, String basePerTon, String customerPerTon,
      boolean fromCustomer, String priceDifference) {}
  public record Result(HouseholdInquiryModels.PriceView source, String pricingMonth, String financeMonth,
      String schemeCode, String financeFile, String formula, String originalPrice, String adjustedPrice,
      String difference, List<Factor> factors) {}

  public Result trial(String unit, long id, Request request) {
    if (id <= 0 || request == null || request.pricingMonth() == null
        || !request.pricingMonth().matches("[0-9]{4}-(0[1-9]|1[0-2])")
        || request.pricingMonth().startsWith("0000"))
      throw new IllegalArgumentException("请选择有效的适用月份");
    var month = YearMonth.parse(request.pricingMonth());
    var customer = CustomerMaterialPrices.copperAndZinc(request.customerCopperPerTon(), request.customerZincPerTon());
    var detail = inquiries.financial(unit, id);
    var row = detail.price();
    var original = detail.evidence().source();
    var key = HouseholdInquiryKey.of(row.materialCode(), row.specification());
    var effective = storage.asOfKeys(unit, List.of(key), month);
    if (effective.size() != 1 || effective.getFirst().getId() != id)
      throw new IllegalArgumentException("该月份的询价来源已变化或尚未生效，请刷新来源后试算");
    var price = new BigDecimal(row.unitPrice());
    if (price.compareTo(new BigDecimal(original.unitPrice())) != 0
        || new BigDecimal(row.householdRate()).compareTo(new BigDecimal(original.householdRate())) != 0
        || !row.sourceMonth().equals(detail.evidence().sourceMonth()))
      throw new IllegalArgumentException("保存的询价与原始P/H及来源月份不一致");
    var financial = original.copperGrams().signum() == 0 && original.zincGrams().signum() == 0 ? null
        : finance.readDefault(new FinanceFactorImportContext(unit, "210", month.toString(), null, null),
            row.materialCode() == null ? "MONTHLY_HOUSEHOLD" : row.materialCode())
            .evidenceForFactors(Set.of(PriceFactorContext.COPPER, PriceFactorContext.ZINC));
    var calculation = HouseholdMetalCalculation.calculate(price,
        new HouseholdMetalCalculation.Inputs(original.copperGrams(), original.zincGrams(), financial), customer);
    var factors = List.of(
        factor("Cu 铜", calculation.copperGrams(), calculation.baseCopper(), calculation.customerCopper(),
            calculation.copperFromCustomer(), calculation.copperDifference()),
        factor("Zn 锌", calculation.zincGrams(), calculation.baseZinc(), calculation.customerZinc(),
            calculation.zincFromCustomer(), calculation.zincDifference()));
    return new Result(row, month.toString(), calculation.financeMonth(),
        financial == null ? null : financial.financial().scope().schemeCode(),
        financial == null ? null : financial.financial().fileName(), calculation.formula(), text(price),
        text(calculation.calculatedW()), text(calculation.calculatedW().subtract(price)), factors);
  }

  private static Factor factor(String name, BigDecimal grams, BigDecimal base, BigDecimal customer,
      boolean fromCustomer, BigDecimal difference) {
    return new Factor(name, text(grams), perTon(base), perTon(customer), fromCustomer, text(difference));
  }
  private static String perTon(BigDecimal value) { return value == null ? null : text(value.movePointRight(3)); }
  private static String text(BigDecimal value) { return value == null ? null : value.stripTrailingZeros().toPlainString(); }
}
