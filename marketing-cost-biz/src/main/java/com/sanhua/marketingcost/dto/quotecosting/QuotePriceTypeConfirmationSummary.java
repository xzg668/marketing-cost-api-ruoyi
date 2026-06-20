package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuotePriceTypeConfirmationSummary {
  private Integer bomRowCount;
  private Integer normalCount;
  private Integer makePartCount;
  private Integer packageComponentCount;
  private Integer missingTypeCount;
  private Integer configuredTypeCount;
  private Integer referencePriceCount;
  private Integer readyForPricePrepareCount;
}
