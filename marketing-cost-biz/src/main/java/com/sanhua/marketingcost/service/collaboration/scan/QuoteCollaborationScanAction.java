package com.sanhua.marketingcost.service.collaboration.scan;

/** 扫描只给出下一步意图；真正创建、关联和复用由后续命令服务完成。 */
public enum QuoteCollaborationScanAction {
  NO_COLLABORATION_REQUIRED,
  CREATE_COLLABORATION,
  LINK_ACTIVE_TASK,
  REUSE_APPROVED_RESULT,
  SYSTEM_BLOCKED
}
