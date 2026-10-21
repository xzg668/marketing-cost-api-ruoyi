package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
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
  private Level previousLevel;

  @BeforeEach void start() {
    previousLevel = logger.getLevel();
    logger.setLevel(Level.INFO);
    events.start();
    logger.addAppender(events);
  }
  @AfterEach void stop() {
    logger.detachAppender(events);
    logger.setLevel(previousLevel);
    events.stop();
    MDC.clear();
  }

  @Test void successfulDirectoryDetailsAreQuietAtInfoAndTraceableAtDebug() {
    String[] operations = {"OA_DIRECTORY_PAGE", "OA_DIRECTORY_HTTP", "JK03_PERSON_DETAIL"};
    MDC.put("oaCallId", "directory-batch");
    for (String operation : operations) {
      try (var call = OaInterfaceLog.start(operation)) { call.success(); }
    }
    assertThat(events.list).isEmpty();
    assertThat(MDC.get("oaCallId")).isEqualTo("directory-batch");

    logger.setLevel(Level.DEBUG);
    for (String operation : operations) {
      try (var call = OaInterfaceLog.start(operation)) { call.success(); }
    }
    assertThat(events.list).hasSize(6).allSatisfy(event -> {
      assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
      assertThat(event.getFormattedMessage()).contains("parentCallId=directory-batch");
    });
    assertThat(messages()).contains("phase=START", "phase=END", "status=SUCCESS", "durationMs=");
  }

  @Test void directoryWarningsAndFailuresRemainVisibleWithoutDebug() {
    for (String status : new String[] {"RETRY", "REJECTED", "UNKNOWN", "UNCONFIRMED", "NOT_SENT"}) {
      try (var call = OaInterfaceLog.start("OA_DIRECTORY_HTTP")) {
        call.result(status, 503, "RETRYABLE_HTTP");
      }
    }
    try (var call = OaInterfaceLog.start("JK03_PERSON_DETAIL")) {
      call.failure(new IllegalStateException("PRIVATE_SECRET"));
    }
    assertThat(events.list).hasSize(6);
    assertThat(events.list.subList(0, 5)).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
    assertThat(events.list.getLast().getLevel()).isEqualTo(Level.ERROR);
    assertThat(messages()).contains("status=FAILED", "status=RETRY", "status=REJECTED")
        .doesNotContain("PRIVATE_SECRET");
  }

  @Test void batchAndWorkflowCallsKeepTheirInfoMilestones() {
    for (String operation : new String[] {"OA_DIRECTORY_SYNC", "I02", "I03", "OA_HTTP"}) {
      try (var call = OaInterfaceLog.start(operation)) { call.success(); }
    }
    assertThat(events.list).hasSize(8)
        .allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.INFO));
  }

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
