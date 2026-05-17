package com.sanhua.marketingcost.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FactorAdjustImportResponse {
  private Long adjustBatchId;
  private String adjustBatchNo;
  private String pricingMonth;
  private String businessUnitType;
  private String usageScope;
  private Integer totalCount;
  private Integer changedCount;
  private Integer noChangeCount;
  private Integer skippedCount;
  private Integer failedCount;
  private String status;
  private List<FactorAdjustPriceDto> rows = new ArrayList<>();
}
