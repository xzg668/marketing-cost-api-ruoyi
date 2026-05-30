package com.sanhua.marketingcost.dto;

import lombok.Getter;
import lombok.Setter;

/** 月度调价从已核算 OA 明细展开出来的候选核算对象。 */
@Getter
@Setter
public class MonthlyRepriceCalcObject {

  private String oaNo;
  private Long oaFormItemId;
  private String productCode;
  private String packageMethod;
  private String customerName;
  private String sourceOaCalcStatus;
}
