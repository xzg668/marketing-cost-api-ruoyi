package com.sanhua.marketingcost.integration.oa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.TraceIdFilter;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class OaHttpLoggingFilterTest {
  private static final String URL = "/open-api/v1/oa/quotation-requests";
  private static final String TOKEN = "PRIVATE_BEARER_TOKEN_TEST_ONLY_123456789";
  private final ObjectMapper json = new ObjectMapper();
  private final OaQuotationService service = mock(OaQuotationService.class);
  private final OaIntegrationProperties properties = new OaIntegrationProperties();
  private final Logger logger = (Logger) LoggerFactory.getLogger(OaInterfaceLog.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private MockMvc http;

  @BeforeEach void start() throws Exception {
    events.start(); logger.addAppender(events);
    properties.setMode(OaIntegrationProperties.Mode.MOCK);
    properties.setEnvironment("TEST");
    var peer = new OaIntegrationProperties.Client();
    peer.setMode(OaIntegrationProperties.Mode.MOCK); peer.setEnvironment("TEST");
    peer.setSourceSystem("WEAVER"); peer.setBusinessUnits(Set.of("COMMERCIAL")); peer.setSecret(TOKEN);
    properties.setClients(Map.of("test", peer));
    when(service.receive(any(), any())).thenReturn(json.readTree("{\"code\":\"0\",\"data\":{\"status\":\"SUCCEEDED\"}}"));
    http = MockMvcBuilders.standaloneSetup(new OaQuotationController(service, properties))
        .setControllerAdvice(new OaResponseLoggingAdvice())
        .addFilters(new TraceIdFilter(), new OaHttpLoggingFilter(), new OaQuotationAuthenticationFilter(properties, json)).build();
  }

  @AfterEach void stop() { logger.detachAppender(events); events.stop(); }

  @Test void preservesRequestAndResponseAndReturnsSearchableCallId() throws Exception {
    String raw = "{\"requestId\":\"WF-001\",\"remark\":\"PRIVATE_REQUEST\"}";
    var result = http.perform(post(URL).header("Authorization", "Bearer " + TOKEN).header("X-Trace-Id", "trace-existing")
            .queryParam("access_token", "PRIVATE_QUERY_TOKEN").contentType("application/json").content(raw))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
        .andExpect(header().string("X-Trace-Id", "trace-existing")).andReturn();
    String callId = result.getResponse().getHeader("X-OA-Call-Id");
    assertThat(callId).isNotBlank();
    verify(service).receive(any(), eq(raw));
    assertThat(messages()).contains("callId=" + callId, "httpStatus=200", "errorCode=0")
        .doesNotContain("PRIVATE_REQUEST", "PRIVATE_QUERY_TOKEN", TOKEN);
  }

  @Test void rejectedAuthenticationIsLoggedBeforeControllerAndNeverLogsHeader() throws Exception {
    http.perform(post(URL).header("Authorization", "Bearer PRIVATE_WRONG_TOKEN").contentType("application/json").content("{}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    verifyNoInteractions(service);
    assertThat(messages()).contains("status=REJECTED", "httpStatus=401", "errorCode=INVALID_TOKEN")
        .doesNotContain("PRIVATE_WRONG_TOKEN");
  }

  @Test void disabledIntegrationAndBusinessConflictsHaveSpecificCodes() throws Exception {
    properties.setMode(OaIntegrationProperties.Mode.DISABLED);
    http.perform(post(URL).contentType("application/json").content("{}"))
        .andExpect(status().isServiceUnavailable());
    assertThat(messages()).contains("errorCode=INTEGRATION_DISABLED", "httpStatus=503");
    properties.setMode(OaIntegrationProperties.Mode.MOCK);
    when(service.receive(any(), any())).thenThrow(OaIntegrationException.conflict("IDEMPOTENCY_CONFLICT", "PRIVATE_REASON"));
    http.perform(post(URL).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content("{}"))
        .andExpect(status().isConflict());
    assertThat(messages()).contains("errorCode=IDEMPOTENCY_CONFLICT").doesNotContain("PRIVATE_REASON");
  }

  @Test void malformedBodyAndUnexpectedFailuresKeepTheirOriginalResponses() throws Exception {
    http.perform(post(URL).header("Authorization", "Bearer " + TOKEN).contentType("application/json")
            .content(new byte[] {(byte) 0xc3, (byte) 0x28}))
        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    when(service.receive(any(), any())).thenThrow(new IllegalStateException("PRIVATE_EXCEPTION_BODY"));
    http.perform(post(URL).header("Authorization", "Bearer " + TOKEN).contentType("application/json").content("{}"))
        .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    assertThat(messages()).contains("errorCode=VALIDATION_FAILED", "errorCode=INTERNAL_ERROR")
        .doesNotContain("PRIVATE_EXCEPTION_BODY");
  }

  @Test void preservesExistingTraceBehaviorAndClearsOaContext() throws Exception {
    var result = http.perform(post(URL).header("Authorization", "Bearer " + TOKEN)
            .header("X-Trace-Id", "upstream:trace/001").contentType("application/json").content("{}"))
        .andExpect(status().isOk()).andReturn();
    assertThat(result.getResponse().getHeader("X-Trace-Id")).isEqualTo("upstream:trace/001");
    assertThat(org.slf4j.MDC.get("oaCallId")).isNull();
    assertThat(org.slf4j.MDC.get("traceId")).isNull();
  }

  @Test void http200BusinessFailureAndIdentityTokenResponseAreHandledWithoutBodyLogging() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new ResultController()).setControllerAdvice(new OaResponseLoggingAdvice())
        .addFilters(new TraceIdFilter(), new OaHttpLoggingFilter()).build();
    mvc.perform(get("/api/v2/technical-data/tasks/1/submit"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(403));
    mvc.perform(get("/api/v2/technical-data/access-tickets/exchange"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.accessToken").value("PRIVATE_SESSION_TOKEN"));
    assertThat(messages()).contains("httpStatus=200", "errorCode=403", "status=REJECTED")
        .doesNotContain("PRIVATE_SESSION_TOKEN", "PRIVATE_ERROR_MESSAGE");
  }

  @ParameterizedTest @ValueSource(strings = {"/integration/v1/workflow-events",
      "/api/v1/integration/oa/debug/technical-dispatch/send", "/api/v1/oa-forms/WF1", "/api/v2/technical-data/assignees",
      "/api/v1/quote-requests/WF1/final-submission", "/api/v2/technical-data/returns"})
  void coversEachOaHttpEntryFamily(String path) { assertThat(OaHttpLoggingFilter.covers(path)).isTrue(); }

  @Test void unrelatedRequestsHaveNoOaLogsOrHeaders() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new ResultController()).setControllerAdvice(new OaResponseLoggingAdvice())
        .addFilters(new OaHttpLoggingFilter()).build();
    mvc.perform(get("/unrelated")).andExpect(status().isOk()).andExpect(header().doesNotExist("X-OA-Call-Id"));
    assertThat(events.list).isEmpty();
  }

  @RestController static class ResultController {
    @GetMapping("/api/v2/technical-data/tasks/1/submit") CommonResult<?> rejected() { return CommonResult.error(403, "PRIVATE_ERROR_MESSAGE"); }
    @GetMapping("/api/v2/technical-data/access-tickets/exchange") CommonResult<?> token() { return CommonResult.success(Map.of("accessToken", "PRIVATE_SESSION_TOKEN")); }
    @GetMapping("/unrelated") CommonResult<?> other() { return CommonResult.success("ok"); }
  }

  private String messages() { return events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b); }
}
