package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;

public interface MonthlyRepriceConfirmService {

  /** 确认发布月度调价批次。 */
  MonthlyRepriceProgressSnapshot confirm(String repriceNo, String operator);
}
