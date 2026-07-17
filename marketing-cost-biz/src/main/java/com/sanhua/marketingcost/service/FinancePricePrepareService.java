package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.financequote.FinancePricePrepareGenerateResult;

/** 基于一个已成功的单产品 OA 价格准备批次生成财务 Cu 场景批次。 */
public interface FinancePricePrepareService {

  FinancePricePrepareGenerateResult generateFromOa(String sourcePrepareNo);

  /** 读取“最终价格生成”阶段已经落库的财务场景快照，不触发重新取价。 */
  FinancePricePrepareGenerateResult loadPreparedFromOa(String sourcePrepareNo);
}
