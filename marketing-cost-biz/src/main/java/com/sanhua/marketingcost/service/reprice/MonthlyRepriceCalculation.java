package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.entity.QuoteBomStatus;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;

/** Internal saved-task input, never accepted as an HTTP request or an ordinary quotation override. */
public record MonthlyRepriceCalculation(String repriceNo, String objectKey,
    MonthlyRepriceBomService.Input input, QuoteBomStatus bom, LocalDateTime priceAsOfTime) {
  public MonthlyRepriceCalculation {
    if (repriceNo == null || !repriceNo.startsWith("MRP1130-") || repriceNo.length() > 64
        || objectKey == null || !objectKey.matches("[0-9a-f]{64}"))
      throw new IllegalArgumentException("保存的1130调价任务身份无效");
    Objects.requireNonNull(input); Objects.requireNonNull(bom); Objects.requireNonNull(priceAsOfTime);
    if (bom.getSyncRecordId() == null || !input.context().costPeriodMonth().equals(bom.getCostPeriodMonth())
        || !YearMonth.from(priceAsOfTime).equals(YearMonth.parse(input.context().costPeriodMonth())))
      throw new IllegalArgumentException("调价月份、取价时点与已保存BOM不一致");
  }
}
