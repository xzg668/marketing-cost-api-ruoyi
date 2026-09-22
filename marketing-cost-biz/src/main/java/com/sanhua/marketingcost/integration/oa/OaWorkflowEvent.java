package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Set;

/** 稳定审批事件；收到报文后仍须验证人员、流程、提交版本与轮次。 */
public record OaWorkflowEvent(String eventId, String documentId, String externalFlowId,
    String eventType, long sequence, Long taskId, Long submissionId, Long technicalVersionId,
    Long round, String operatorExternalId, String reason) {
  public static OaWorkflowEvent map(int schemaVersion, JsonNode payload) {
    JsonNode event = schemaVersion == 1 ? payload : payload.path("event");
    String type = field(event, "eventType", 32);
    if (!Set.of("TECH_APPROVED", "TECH_RETURNED", "FINANCE_ENTERED").contains(type)) {
      throw OaIntegrationException.invalid("UNKNOWN_EVENT_TYPE", "不支持的审批或节点事件");
    }
    boolean technical = !"FINANCE_ENTERED".equals(type);
    return new OaWorkflowEvent(
        field(event, "eventId", 128),
        field(event, "documentId", 128),
        field(event, "externalFlowId", 128), type,
        positiveLong(event.path("sequence"), "sequence"),
        technical ? positiveLong(event.path("taskId"), "taskId") : null,
        technical ? positiveLong(event.path("submissionId"), "submissionId") : null,
        technical ? positiveLong(event.path("technicalVersionId"), "technicalVersionId") : null,
        technical ? positiveLong(event.path("round"), "round") : null,
        field(event, "operatorExternalId", 128),
        event.hasNonNull("reason") ? field(event, "reason", 512) : null);
  }
  static long positiveLong(JsonNode value, String field) {
    try {
      if (!value.isIntegralNumber() && !value.isTextual()) throw new IllegalArgumentException();
      String text = value.asText();
      if (!text.matches("[1-9][0-9]*")) throw new IllegalArgumentException();
      return Long.parseLong(text);
    } catch (RuntimeException ex) { throw OaIntegrationException.invalid("INVALID_ID_OR_VERSION", field + " 必须是正整数"); }
  }

  static String field(JsonNode node, String key, int max) {
    try { return OaMessageCodec.text(node, key, max); }
    catch (OaIntegrationException ex) { throw OaIntegrationException.invalid(ex.code(), ex.getMessage()); }
  }

}
