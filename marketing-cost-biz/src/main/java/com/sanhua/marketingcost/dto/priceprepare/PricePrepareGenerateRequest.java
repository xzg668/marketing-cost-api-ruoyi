package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareGenerateRequest {
  private String oaNo;
  private java.util.List<String> topProductCodes;
  private String periodMonth;
  private String bomPurpose;
  private String sourceType;
}
