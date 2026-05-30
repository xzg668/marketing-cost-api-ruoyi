package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 当前业务单元月度调价锁定状态。 */
@Getter
@Setter
public class MonthlyRepriceActiveLockDto {
  private boolean locked;
  private String repriceNo;
  private String pricingMonth;
  private String businessUnitType;
  private String status;
  private String message;

  public static MonthlyRepriceActiveLockDto unlocked() {
    MonthlyRepriceActiveLockDto dto = new MonthlyRepriceActiveLockDto();
    dto.setLocked(false);
    return dto;
  }
}
