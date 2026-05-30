package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceLinkedPricePrepareResult;

public interface MonthlyRepriceLinkedPricePrepareService {

  /** 为指定月度调价批次生成 MONTHLY_ADJUST 联动价结果。 */
  MonthlyRepriceLinkedPricePrepareResult prepare(String repriceNo, String operator);
}
