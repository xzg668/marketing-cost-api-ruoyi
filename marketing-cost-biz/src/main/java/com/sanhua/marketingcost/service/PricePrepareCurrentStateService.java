package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.PricePrepareBatch;

/** 原子发布最终价格候选，并清理未被工作区或成本版本引用的临时批次。 */
public interface PricePrepareCurrentStateService {

  void finalizeBatch(PricePrepareBatch batch);

  /** 业务缺口已提升到协作事实后，删除未被工作区或成功成本引用的失败候选。 */
  boolean discardPromotedFailedAttempt(String prepareNo);
}
