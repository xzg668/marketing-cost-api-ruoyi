package com.sanhua.marketingcost.service.effectivebom;

/** 客户场景月度最终 BOM 首次冻结和当月复用入口。 */
public interface QuoteBomMonthlyFreezeService {

  /** 进入第2步时保存可覆盖的计价候选，不改变月度卡片的 DRAFT 状态。 */
  default QuoteBomMonthlyFreezeResult stage(QuoteBomMonthlyFreezeCommand command) {
    throw new UnsupportedOperationException("当前实现不支持暂存计价BOM");
  }

  QuoteBomMonthlyFreezeResult freeze(QuoteBomMonthlyFreezeCommand command);
}
