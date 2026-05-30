package com.sanhua.marketingcost.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 月度调价桥接到通用成本核算任务队列的提交请求。 */
@Getter
@Setter
public class CostRunMonthlyRepriceSubmitRequest {

  private String repriceNo;
  private String pricingMonth;
  private LocalDateTime priceAsOfTime;
  private String businessUnitType;
  private Long adjustBatchId;
  private String bomSourcePolicy;
  private String createdBy;
  private String createdName;
  private List<MonthlyRepriceCalcObject> calcObjects;
}
