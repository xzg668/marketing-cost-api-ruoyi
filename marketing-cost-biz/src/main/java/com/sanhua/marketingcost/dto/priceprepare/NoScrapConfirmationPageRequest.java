package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NoScrapConfirmationPageRequest {
  private String businessUnitType;
  private String materialNo;
  private String status;
  private String sourceOaNo;
  private Integer page;
  private Integer pageSize;
}
