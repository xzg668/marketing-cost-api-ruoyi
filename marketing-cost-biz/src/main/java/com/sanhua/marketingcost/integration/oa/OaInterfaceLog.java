package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** OA 边界日志：只记录明确的业务标识和结果，不序列化报文、凭证、意见或异常正文。 */
public final class OaInterfaceLog {
  private static final Logger log = LoggerFactory.getLogger(OaInterfaceLog.class);
  private static final Set<String> FIELDS = Set.of("requestId", "workflowRequestId", "formNo", "processCode",
      "taskId", "submissionId", "resultSubmissionId", "eventType", "employeeNo", "userid", "userId",
      "messageId", "interfaceType", "sourceSystem", "environment", "mode", "direction", "method", "endpoint",
      "version", "formVersion", "state", "attempt", "retryDelaySeconds", "stage", "itemCount", "page",
      "total", "selectable", "batchId", "remarkChars", "dataKey", "cache", "exceptionType", "causeType",
      "location", "sqlState", "databaseCode", "field", "trigger", "expectedVersion", "appliedVersion");
  private static final Set<String> BUSINESS_FIELDS = Set.of("requestId", "workflowRequestId", "formNo", "processCode",
      "taskId", "submissionId", "resultSubmissionId", "eventType", "employeeNo", "userid", "formVersion", "version", "state");

  private OaInterfaceLog() {}

  public static Call start(String operation) { return new Call(operation); }

  /** 防止外部编号通过换行、长文本或 URL 查询串污染日志；不用于业务校验。 */
  public static String identifier(Object value) {
    if (value == null) return "-";
    String text = value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>
        ? value.toString() : "[omitted]";
    return text.matches("[A-Za-z0-9._:/\\[\\]-]{1,180}") ? text : "[omitted]";
  }

  public static final class Call implements AutoCloseable {
    private final String operation;
    private final String callId = UUID.randomUUID().toString();
    private final String parentCallId = MDC.get("oaCallId");
    private final long started = System.nanoTime();
    private final Map<String, String> fields = new LinkedHashMap<>();
    private String status = "UNCONFIRMED";
    private String code = "-";
    private Integer httpStatus;

    private Call(String operation) {
      this.operation = identifier(operation);
      MDC.put("oaCallId", callId);
      log.info("OA_INTERFACE phase=START operation={} callId={} parentCallId={}",
          this.operation, callId, identifier(parentCallId));
    }

    public String id() { return callId; }

    public Call field(String key, Object value) {
      if (key != null && FIELDS.contains(key)) fields.put(key, identifier(value));
      return this;
    }

    public Call business(JsonNode body) {
      if (body != null && body.isObject()) {
        for (String key : BUSINESS_FIELDS) {
          JsonNode value = body.get(key);
          if (value != null && (value.isTextual() || value.isNumber())) field(key, value.asText());
        }
      }
      return this;
    }

    public void result(String status, Integer httpStatus, String code) {
      this.status = identifier(status);
      this.httpStatus = httpStatus;
      this.code = identifier(code);
    }

    public void success() { result("SUCCESS", null, null); }

    public void failure(Throwable failure) {
      status = "FAILED";
      if ("-".equals(code)) code = identifier(failure.getClass().getSimpleName());
      field("exceptionType", failure.getClass().getSimpleName());
      Throwable cause = failure.getCause();
      if (cause != null) field("causeType", cause.getClass().getSimpleName());
      for (StackTraceElement frame : failure.getStackTrace()) {
        if (frame.getClassName().startsWith("com.sanhua.marketingcost.")) {
          field("location", frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber());
          break;
        }
      }
      if (failure instanceof OaIntegrationException error) {
        result("REJECTED", error.httpStatus().value(), error.code());
        field("stage", error.stage());
      } else if (failure instanceof OaQuotationValidationException error) {
        result("REJECTED", 400, "VALIDATION_FAILED");
        if (!error.errors().isEmpty()) field("field", error.errors().getFirst().field());
      } else if (failure instanceof org.springframework.dao.DataAccessException error
          && error.getMostSpecificCause() instanceof java.sql.SQLException sql) {
        field("sqlState", sql.getSQLState()).field("databaseCode", sql.getErrorCode());
      }
    }

    @Override public void close() {
      try {
        var event = "FAILED".equals(status) ? log.atError()
            : Set.of("REJECTED", "UNKNOWN", "UNCONFIRMED", "RETRY", "NOT_SENT").contains(status) ? log.atWarn() : log.atInfo();
        event.log("OA_INTERFACE phase=END operation={} callId={} parentCallId={} status={} httpStatus={} errorCode={} durationMs={} context={}",
            operation, callId, identifier(parentCallId), status, httpStatus, code,
            (System.nanoTime() - started) / 1_000_000, fields);
      } finally {
        if (parentCallId == null) MDC.remove("oaCallId"); else MDC.put("oaCallId", parentCallId);
      }
    }
  }
}
