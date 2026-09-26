package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/** SUCCESS 仅表示 OA 明确确认本次 submitRequest；不推断待办编号或后续审批状态。 */
public record OaWorkflowResult(
    String callId, Status status, Integer httpStatus, String errorCode, String message,
    String requestId, JsonNode response, long durationMs) {
  public enum Status {
    NOT_SENT, SUCCESS, REJECTED, UNKNOWN
  }
}
