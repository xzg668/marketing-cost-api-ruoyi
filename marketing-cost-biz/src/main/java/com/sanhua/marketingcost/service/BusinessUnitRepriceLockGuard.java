package com.sanhua.marketingcost.service;

/** 普通 OA 成本核算的月度调价业务单元锁校验。 */
public interface BusinessUnitRepriceLockGuard {

  /**
   * 校验指定 OA 是否处于月度调价锁定业务单元。
   *
   * <p>只用于普通 OA 成本核算入口。月度调价 Worker 和确认等控制动作不应调用本 guard。
   */
  void assertCostRunAllowed(String oaNo);
}
