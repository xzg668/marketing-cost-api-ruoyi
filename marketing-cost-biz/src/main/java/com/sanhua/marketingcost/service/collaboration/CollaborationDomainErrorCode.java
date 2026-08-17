package com.sanhua.marketingcost.service.collaboration;

/** QCBP协作领域对外稳定错误码。 */
public enum CollaborationDomainErrorCode {
  TASK_NOT_FOUND,
  TASK_VERSION_CONFLICT,
  STATE_TRANSITION_INVALID,
  TASK_ASSIGNEE_MISMATCH,
  QUOTE_LINK_READ_ONLY,
  IDEMPOTENCY_CONFLICT,
  INTEGRATION_DISABLED
}
