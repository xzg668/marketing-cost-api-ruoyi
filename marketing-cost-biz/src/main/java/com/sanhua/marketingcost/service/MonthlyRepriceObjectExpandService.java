package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceObjectExpandResult;

public interface MonthlyRepriceObjectExpandService {

  /** 按已核算 OA 明细展开指定月度调价批次的核算任务。 */
  MonthlyRepriceObjectExpandResult expand(String repriceNo, String operator);
}
