package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuotePriceTypeRecognitionSummary {
  private Integer bomRowCount;
  private Integer normalCount;
  private Integer makePartCount;
  private Integer packageComponentCount;
  private Integer missingSourceCount;
  private Integer candidateSourceCount;
  private Integer priceableCount;
}
