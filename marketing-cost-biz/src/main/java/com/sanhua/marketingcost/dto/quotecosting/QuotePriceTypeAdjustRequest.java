package com.sanhua.marketingcost.dto.quotecosting;

import lombok.Data;

@Data
public class QuotePriceTypeAdjustRequest {
  private String materialCode;
  private String materialName;
  private String objectType;
  private String priceType;
  private String effectiveFrom;
  private String reason;
}
