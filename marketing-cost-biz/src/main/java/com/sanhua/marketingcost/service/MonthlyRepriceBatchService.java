package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateRequest;
import com.sanhua.marketingcost.dto.MonthlyRepriceBatchCreateResponse;

public interface MonthlyRepriceBatchService {

  /**
   * 创建月度调价批次。
   *
   * <p>本方法只创建控制批次，不展开 OA 核算对象，也不触发成本核算。
   */
  MonthlyRepriceBatchCreateResponse createBatch(
      MonthlyRepriceBatchCreateRequest request, String operator);

  /** 判断指定业务单元是否已有未结束的月度调价批次。 */
  boolean hasActiveBatch(String businessUnitType);
}
