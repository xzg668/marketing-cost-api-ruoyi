package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CmsEffectiveSourceRefreshRequest {
  private Integer costYear;
  private String parentCode;
  private String sourceType;
  private String newPeriod;
  private String subjectCode;
  private String refreshReason;
}
