package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;

class OaInterfaceLogTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(OaInterfaceLog.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();

  @BeforeEach void start() { events.start(); logger.addAppender(events); }
  @AfterEach void stop() { logger.detachAppender(events); events.stop(); MDC.clear(); }

  @Test void nestedCallsCarryParentAndRestoreContextAfterExceptions() {
    MDC.put("traceId", "trace-001");
    String parent;
    try (var outer = OaInterfaceLog.start("I03")) {
      parent = outer.id();
      try (var inner = OaInterfaceLog.start("OA_HTTP")) {
        assertThat(MDC.get("oaCallId")).isEqualTo(inner.id());
        inner.result("REJECTED", 200, "1200302");
      }
      assertThat(MDC.get("oaCallId")).isEqualTo(parent);
      outer.success();
    }
    assertThat(MDC.get("oaCallId")).isNull();
    assertThat(MDC.get("traceId")).isEqualTo("trace-001");
    assertThat(messages()).contains("parentCallId=" + parent, "status=REJECTED", "errorCode=1200302", "durationMs=");
  }

  @Test void logsOnlyApprovedIdentifiersAndNeverCredentialOrPayloadText() throws Exception {
    var body = new ObjectMapper().readTree("""
        {"requestId":"WF-001","userid":"00123","remark":"PRIVATE_PRICE_AND_REMARK",
        "access_token":"PRIVATE_ACCESS_TOKEN","code":"PRIVATE_ONE_TIME_CODE",
        "formData":{"content":"PRIVATE_DETAILS"}}
        """);
    try (var call = OaInterfaceLog.start("I03")) {
      call.business(body).field("appSecret", "PRIVATE_APP_SECRET").field("password", "PRIVATE_PASSWORD")
          .field("endpoint", "/path?access_token=PRIVATE_URL_TOKEN").field("formNo", "FAKE\nstatus=SUCCESS");
      call.failure(new RuntimeException("PRIVATE_EXCEPTION_SECRET", new RuntimeException("PRIVATE_CAUSE_SECRET")));
    }
    assertThat(messages()).contains("requestId=WF-001", "userid=00123", "exceptionType=RuntimeException", "[omitted]")
        .doesNotContain("PRIVATE_", "FAKE\n", "status=SUCCESS");
  }

  @Test void recordsDatabaseDiagnosticsWithoutSqlOrExceptionBody() {
    try (var call = OaInterfaceLog.start("I01")) {
      call.failure(new DataIntegrityViolationException("PRIVATE_SQL", new SQLException("PRIVATE_VALUE", "23000", 1062)));
    }
    assertThat(messages()).contains("sqlState=23000", "databaseCode=1062", "status=FAILED")
        .doesNotContain("PRIVATE_SQL", "PRIVATE_VALUE");
  }

  @Test void logsBusinessErrorCodeAndValidationField() {
    try (var call = OaInterfaceLog.start("I04")) {
      call.failure(OaIntegrationException.conflict("SEQUENCE_CONFLICT", "PRIVATE_REASON"));
    }
    try (var call = OaInterfaceLog.start("I01")) {
      call.failure(new OaQuotationValidationException("mainData.cpsyb", "PRIVATE_FIELD_VALUE"));
    }
    assertThat(messages()).contains("errorCode=SEQUENCE_CONFLICT", "httpStatus=409", "field=mainData.cpsyb")
        .doesNotContain("PRIVATE_");
  }

  @Test void unsuccessfulScopeHasNoFabricatedSuccessAndExistingParentSurvives() {
    MDC.put("oaCallId", "existing-parent");
    try {
      try (var ignored = OaInterfaceLog.start("OA_HTTP")) { throw new IllegalStateException("PRIVATE"); }
    } catch (IllegalStateException expected) { /* 验证 finally 清理和未确认结果 */ }
    assertThat(MDC.get("oaCallId")).isEqualTo("existing-parent");
    assertThat(messages()).contains("status=UNCONFIRMED").doesNotContain("PRIVATE", "status=SUCCESS");
  }

  private String messages() { return events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b); }
}
