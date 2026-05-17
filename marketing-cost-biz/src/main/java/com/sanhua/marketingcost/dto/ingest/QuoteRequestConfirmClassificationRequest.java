package com.sanhua.marketingcost.dto.ingest;

import lombok.Data;

@Data
public class QuoteRequestConfirmClassificationRequest {
  private String quoteScenario;
  private String businessUnitType;
}
