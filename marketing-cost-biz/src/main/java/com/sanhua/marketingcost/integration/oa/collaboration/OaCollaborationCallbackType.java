package com.sanhua.marketingcost.integration.oa.collaboration;

/** OA接入后至少支持的四类入站回调。 */
public enum OaCollaborationCallbackType {
  OA_TASK_CREATED_ACK,
  OA_TASK_ASSIGNEE_CHANGED,
  OA_PROCESS_WITHDRAWN,
  OA_PROCESS_CANCELLED
}
