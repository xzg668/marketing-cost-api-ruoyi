package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.dto.MonthlyRepriceProgressSnapshot;
import com.sanhua.marketingcost.entity.MonthlyRepriceBatch;

/** 月度调价关键操作审计服务。 */
public interface MonthlyRepriceAuditService {

  /** 记录批次开始进入 Worker 核算阶段。 */
  void recordStartCalc(MonthlyRepriceBatch batch, String operator, String changeSummary);

  /** 记录批次全部任务核算完成并进入待确认。 */
  void recordCalcCompleted(MonthlyRepriceBatch before, MonthlyRepriceProgressSnapshot after);

  /** 记录批次全部任务收口后存在失败。 */
  void recordCalcFailed(MonthlyRepriceBatch before, MonthlyRepriceProgressSnapshot after);

  /** 记录普通 OA 核算因为同业务单元正在月度调价而被拦截。 */
  void recordOaCostRunBlocked(MonthlyRepriceBatch batch, String oaNo, String operator);
}
