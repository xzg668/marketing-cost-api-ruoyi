package com.sanhua.marketingcost.service.collaboration.scan;

/** 扫描只给出下一步意图；真正创建、关联和复用由后续命令服务完成。 */
public enum QuoteCollaborationScanAction {
  NO_COLLABORATION_REQUIRED,
  /** 缺少全局物料价格类型；由财务维护主数据，不创建技术补价任务。 */
  MAINTAIN_PRICE_TYPE,
  CREATE_COLLABORATION,
  LINK_ACTIVE_TASK,
  REUSE_APPROVED_RESULT,
  SYSTEM_BLOCKED
}
