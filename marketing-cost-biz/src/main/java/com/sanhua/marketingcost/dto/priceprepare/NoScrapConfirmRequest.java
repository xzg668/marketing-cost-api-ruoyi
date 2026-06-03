package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoScrapConfirmRequest {
  private String businessUnitType;
  private String materialNo;
  private String materialName;
  private String effectiveFromMonth;
  private String effectiveToMonth;
  private String confirmReason;
  private String sourceOaNo;
  private Long sourceGapId;
}
