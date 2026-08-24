package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;

/** 通用成本核算批次进度服务。 */
public interface CostRunTaskProgressService {

  CostRunBatchProgressSnapshot refreshBatchProgress(String batchNo);

  /** 只读计算当前快照，供页面短轮询使用，不更新时间戳或批次状态。 */
  CostRunBatchProgressSnapshot getBatchProgress(String batchNo);
}
