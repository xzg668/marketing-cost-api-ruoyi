package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunPartItemDto;
import java.util.List;
import java.util.function.IntConsumer;

public interface CostRunPartItemService {
  /** 兼容旧 caller（controller 直查），不上报进度 */
  default List<CostRunPartItemDto> listByOaNo(String oaNo) {
    return listByOaNo(oaNo, p -> {});
  }

  /**
   * T16：取价 + 上报进度。每完成 1 个部品 resolve 调 {@code progress.accept(0..100)}，
   * 调用方把 0..100 子百分比映射到全局进度区间（trial doRun 用 [5-60]）。
   */
  List<CostRunPartItemDto> listByOaNo(String oaNo, IntConsumer progress);

  List<CostRunPartItemDto> listStoredByOaNo(String oaNo);
}
