package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.JsonNode;

/** 接口回执与人工核实凭据通过 callId/errorCode 区分；均不推断后续审批状态。 */
public record OaWorkflowResult(
    String callId, Status status, Integer httpStatus, String errorCode, String message,
    String requestId, JsonNode response, long durationMs) {
  public enum Status {
    NOT_SENT, SUCCESS, REJECTED, UNKNOWN
  }
}
