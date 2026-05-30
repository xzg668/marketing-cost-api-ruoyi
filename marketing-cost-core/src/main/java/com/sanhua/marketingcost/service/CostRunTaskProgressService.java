package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.CostRunBatchProgressSnapshot;

/** 通用成本核算批次进度服务。 */
public interface CostRunTaskProgressService {

  CostRunBatchProgressSnapshot refreshBatchProgress(String batchNo);
}
