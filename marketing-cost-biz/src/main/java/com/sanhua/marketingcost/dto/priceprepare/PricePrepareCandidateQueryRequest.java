package com.sanhua.marketingcost.dto.priceprepare;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PricePrepareCandidateQueryRequest {
  private String keyword;
  private String periodMonth;
  private String ownerScope;
  private String calcStatus;
  private String prepareStatus;
  private Boolean onlyPending;
  private Integer page;
  private Integer pageSize;
}
