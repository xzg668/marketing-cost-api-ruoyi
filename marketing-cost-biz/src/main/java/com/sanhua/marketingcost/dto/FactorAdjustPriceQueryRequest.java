package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustPriceQueryRequest {
  private Long adjustBatchId;
  private Long factorIdentityId;
  private String keyword;
  private String status;
  private Integer limit;
  private Integer page;
  private Integer pageSize;
}
