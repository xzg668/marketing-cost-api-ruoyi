package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MonthlyRepriceBatchCreateRequest {
  /** 调价月份，格式 YYYY-MM。 */
  private String pricingMonth;

  /** 本次月度调价所属业务单元。 */
  private String businessUnitType;

  /** 发起备注，记录业务总监本次调价说明。 */
  private String remark;
}
