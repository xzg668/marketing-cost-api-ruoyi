package com.sanhua.marketingcost.service.reprice;

import com.sanhua.marketingcost.config.MonthlyRepriceProperties;
import com.sanhua.marketingcost.mapper.QuotePricingPeriodMapper;
import com.sanhua.marketingcost.service.QuotePricingPeriod;
import java.time.YearMonth;
import org.springframework.stereotype.Service;

/**
 * The existing director confirmation publishes the active pricing month.
 * No calendar fallback or cache: confirmation is visible immediately to API and worker processes.
 */
@Service
public class ConfirmedQuotePricingPeriod implements QuotePricingPeriod {
  private final QuotePricingPeriodMapper mapper;
  private final MonthlyRepriceProperties properties;

  public ConfirmedQuotePricingPeriod(
      QuotePricingPeriodMapper mapper, MonthlyRepriceProperties properties) {
    this.mapper = mapper;
    this.properties = properties;
  }

  @Override
  public String currentMonth(String businessUnitType) {
    MonthlyRepriceContract.unit(businessUnitType);
    String month = mapper.confirmedMonth(businessUnitType);
    if (month == null) {
      // Existing installations retain their last successfully used ordinary month until first confirmation.
      // Batch-owned results are excluded, including successfully computed but unconfirmed repricing.
      month = mapper.existingQuotationMonth(businessUnitType);
    }
    if (month == null) {
      // An empty installation needs its verified launch month, never an inferred calendar rollover.
      month = properties.requireGoLiveMonth(businessUnitType).toString();
    }
    return YearMonth.parse(month).toString();
  }
}
