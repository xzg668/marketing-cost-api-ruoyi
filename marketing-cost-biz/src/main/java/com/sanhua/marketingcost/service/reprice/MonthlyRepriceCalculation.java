package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;

/** Internal saved-task input, never accepted as an HTTP request or an ordinary quotation override. */
public record MonthlyRepriceCalculation(String repriceNo, String objectKey,
    MonthlyRepriceBomService.Input input, QuoteBomStatus bom, LocalDateTime priceAsOfTime,
    com.sanhua.marketingcost.service.costing.CostRunPreparedInputs preparedInputs) {
  public MonthlyRepriceCalculation(String repriceNo, String objectKey,
      MonthlyRepriceBomService.Input input, QuoteBomStatus bom, LocalDateTime priceAsOfTime) {
    this(repriceNo, objectKey, input, bom, priceAsOfTime, null);
  }

  public com.sanhua.marketingcost.dto.CostRunContext costContext() {
    var context = com.sanhua.marketingcost.dto.CostRunContext.monthlyReprice(
        input.context().costPeriodMonth(), repriceNo, input.form().getBusinessUnitType(), priceAsOfTime,
        "MONTHLY_SNAPSHOT", input.form().getOaNo(), input.item().getId(), input.context().productCode(),
        input.item().getPackageMethod(), input.form().getCustomer(), objectKey);
    context.setQuotationInput(new com.sanhua.marketingcost.dto.CostRunQuotationInput(input.form(), input.item()));
    context.setPriceOrgCode(input.context().organization().priceOrgCode());
    context.setMaterialOrganizationCode(input.context().organization().materialOrganizationCode());
    context.setPriceScenarioType(com.sanhua.marketingcost.enums.QuotePriceScenarioType.FINANCE_QUOTE_BASE.name());
    context.setPreparedInputs(preparedInputs);
    return context;
  }

  public MonthlyRepriceCalculation withPreparedInputs(
      com.sanhua.marketingcost.service.costing.CostRunPreparedInputs prepared) {
    return new MonthlyRepriceCalculation(repriceNo, objectKey, input, bom, priceAsOfTime,
        Objects.requireNonNull(prepared));
  }
  public MonthlyRepriceCalculation {
    if (repriceNo == null || !repriceNo.startsWith("MRP1130-") || repriceNo.length() > 64
        || objectKey == null || !(objectKey.matches("[0-9a-f]{64}")
            || objectKey.matches("CUSTOMER-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
      throw new IllegalArgumentException("保存的1130调价任务身份无效");
    Objects.requireNonNull(input); Objects.requireNonNull(bom); Objects.requireNonNull(priceAsOfTime);
    if (bom.getSyncRecordId() == null || !input.context().costPeriodMonth().equals(bom.getCostPeriodMonth())
        || !YearMonth.from(priceAsOfTime).equals(YearMonth.parse(input.context().costPeriodMonth())))
      throw new IllegalArgumentException("调价月份、取价时点与已保存BOM不一致");
  }
}
