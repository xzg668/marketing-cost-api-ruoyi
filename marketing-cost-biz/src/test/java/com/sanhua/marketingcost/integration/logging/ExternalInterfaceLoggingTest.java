package com.sanhua.marketingcost.integration.logging;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.config.TraceIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

class ExternalInterfaceLoggingTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalInterfaceLog.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();

  @BeforeEach void start() { events.start(); logger.addAppender(events); }
  @AfterEach void stop() { logger.detachAppender(events); events.stop(); MDC.clear(); }

  @Test void retainsBusinessJsonAndRedactsNestedCredentialsAndEchoes() throws Exception {
    MDC.put("traceId", "trace-123");
    MDC.put("oaCallId", "oa-456");
    try (var call = ExternalInterfaceLog.start("OA", "OUTBOUND", "POST",
        URI.create("https://example.test/submit?access_token=QUERY_SECRET&userType=JOB_NUM"),
        Map.of("Authorization", List.of("Bearer HEADER_SECRET"), "Content-Type", List.of("application/json")))) {
      call.request("""
          {"requestId":"REQ-001","formNo":"报价-001","price":1.2300,"password":"BODY_SECRET",
           "items":[{"fieldName":"appSecret","fieldValue":"PAIR_SECRET"}]}
          """);
      call.response(400, Map.of("Set-Cookie", List.of("SESSION_SECRET")), """
          {"code":"FIELD_INVALID","message":"bad field; QUERY_SECRET BODY_SECRET HEADER_SECRET PAIR_SECRET",
           "data":{"accessToken":"RESPONSE_SECRET"},"field":"price"}
          """);
    }
    String text = messages();
    assertThat(text).contains("REQ-001", "报价-001", "FIELD_INVALID", "price", "trace-123", "oa-456", "JOB_NUM")
        .doesNotContain("QUERY_SECRET", "BODY_SECRET", "HEADER_SECRET", "PAIR_SECRET", "RESPONSE_SECRET", "SESSION_SECRET");
    JsonNode event = last();
    assertThat(event.at("/request/json/price").decimalValue()).isEqualByComparingTo("1.2300");
    assertThat(event.path("outcome").asText()).isEqualTo("HTTP_ERROR");
  }

  @Test void capturesMalformedAndOversizedPayloadReasonsWithoutLeakingPartialSecrets() throws Exception {
    try (var call = ExternalInterfaceLog.start("OA", "INBOUND", "POST", URI.create("/integration/v1/workflow-events"), Map.of())) {
      call.request("{\"password\":\"BROKEN_SECRET");
      call.response(400, Map.of(), "{\"message\":\"" + "x".repeat(ExternalInterfaceLog.MAX_BODY_BYTES) + "\"}");
    }
    assertThat(last().at("/request/captureStatus").asText()).isEqualTo("NON_JSON_OR_INVALID_JSON");
    assertThat(last().at("/request/errorLine").asInt()).isPositive();
    assertThat(last().at("/response/captureStatus").asText()).isEqualTo("OVER_LIMIT");
    assertThat(messages()).doesNotContain("BROKEN_SECRET");
  }

  @Test void authenticationResponsesKeepOnlyStatusAndNeverAuthorizationCodes() throws Exception {
    try (var call = ExternalInterfaceLog.start("OA_AUTH", "OUTBOUND", "POST", URI.create("https://example.test/oauth/accessToken?code=CODE_SECRET"), Map.of())) {
      call.request("app_secret=FORM_SECRET");
      call.response(200, Map.of(), "{\"errcode\":0,\"code\":\"RETURN_SECRET\",\"data\":\"TOKEN_SECRET\",\"message\":\"FORM_SECRET\"}");
    }
    assertThat(messages()).doesNotContain("CODE_SECRET", "FORM_SECRET", "RETURN_SECRET", "TOKEN_SECRET");
    assertThat(last().at("/response/json/errcode").asInt()).isZero();
  }

  @Test void preservesInboundBytesResponseAndTraceWhileRecordingRequestAndAcknowledgement() throws Exception {
    Echo controller = new Echo();
    var mvc = MockMvcBuilders.standaloneSetup(controller)
        .addFilters(new TraceIdFilter(), new ExternalHttpLoggingFilter()).build();
    String raw = " { \"requestId\" : \"RAW-001\", \"name\":\"中文\", \"password\":\"PRIVATE\" } ";
    mvc.perform(post("/integration/v1/workflow-events").header("X-Trace-Id", "trace-inbound")
            .contentType("application/json").content(raw.getBytes(StandardCharsets.UTF_8)))
        .andExpect(status().isOk()).andExpect(content().json("{\"code\":\"0\",\"requestId\":\"RAW-001\"}"))
        .andExpect(header().exists("X-Interface-Call-Id"));
    assertThat(controller.received).isEqualTo(raw);
    assertThat(last().at("/request/json/name").asText()).isEqualTo("中文");
    assertThat(last().at("/response/json/requestId").asText()).isEqualTo("RAW-001");
    assertThat(last().path("traceId").asText()).isEqualTo("trace-inbound");
    assertThat(messages()).doesNotContain("PRIVATE");
  }

  @Test void unauthorizedUnreadBodiesAreExplicitAndResponseStillReachesCaller() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new Echo())
        .addFilters(new ExternalHttpLoggingFilter(), (jakarta.servlet.Filter) (request, response, chain) -> {
          var http = (jakarta.servlet.http.HttpServletResponse) response;
          http.setStatus(401); http.setContentType("application/json");
          http.getWriter().write("{\"code\":\"INVALID_TOKEN\"}");
        }).build();
    mvc.perform(post("/open-api/v1/oa/quotation-requests").contentType("application/json").content("{\"requestId\":\"NOT_CONSUMED\"}"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    assertThat(last().at("/request/captureStatus").asText()).isEqualTo("NOT_READ_BY_HANDLER");
    assertThat(last().at("/response/json/code").asText()).isEqualTo("INVALID_TOKEN");
  }

  @Test void originalExceptionIsPropagatedAndCannotBecomeSuccessfulLog() throws Exception {
    IOException original = new IOException("PRIVATE_CREDENTIAL");
    HttpClient client = mock(HttpClient.class);
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.test/query")).GET().build();
    when(client.send(eq(request), any(HttpResponse.BodyHandler.class))).thenThrow(original);
    assertThatThrownBy(() -> ExternalHttpExchange.sendJson("DRAWING", client, request, null)).isSameAs(original);
    verify(client, times(1)).send(eq(request), any(HttpResponse.BodyHandler.class));
    assertThat(last().path("outcome").asText()).isEqualTo("FAILED");
    assertThat(last().path("exceptionType").asText()).isEqualTo("IOException");
    assertThat(messages()).doesNotContain("PRIVATE_CREDENTIAL");
    try (var call = ExternalInterfaceLog.start("OA", "INBOUND", "POST", URI.create("/integration/test"), Map.of())) {
      call.failure(new IllegalStateException());
      call.response(200, Map.of(), "{}");
    }
    assertThat(last().path("outcome").asText()).isEqualTo("FAILED");
  }

  @Test void outgoingHttpResponseObjectAndBusinessErrorBodyRemainUnchanged() throws Exception {
    HttpClient client = mock(HttpClient.class);
    HttpRequest request = HttpRequest.newBuilder(URI.create("https://example.test/query"))
        .POST(HttpRequest.BodyPublishers.ofString("{\"page\":2}")).build();
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(200);
    when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
    when(response.body()).thenReturn("{\"errcode\":-4,\"errmsg\":\"IP不允许访问\"}");
    when(client.send(eq(request), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    assertThat(ExternalHttpExchange.sendJson("OA_DIRECTORY", client, request, "{\"page\":2}")).isSameAs(response);
    assertThat(last().at("/request/json/page").asInt()).isEqualTo(2);
    assertThat(last().at("/response/json/errcode").asInt()).isEqualTo(-4);
    assertThat(last().path("outcome").asText()).isEqualTo("HTTP_RESPONSE");
  }

  @Test void ordinaryApplicationPagesDoNotProduceExternalPayloadLogs() throws Exception {
    var mvc = MockMvcBuilders.standaloneSetup(new Echo()).addFilters(new ExternalHttpLoggingFilter()).build();
    mvc.perform(post("/ordinary").content("{}"));
    assertThat(events.list).isEmpty();
  }

  @Test void reviewTaskHeadersAreTraceableWithoutLoggingAuthenticationHeaders() throws Exception {
    try (var call = ExternalInterfaceLog.start("DRAWING_PDF", "INBOUND", "POST",
        URI.create("/integration/v1/drawings/review-results"), Map.of(
            "X-Review-Task", List.of("task-001"), "Idempotency-Key", List.of("task-001"),
            "X-Request-ID", List.of("attempt-002"), "Authorization", List.of("Bearer CALLBACK_SECRET")))) {
      call.request("{\"taskId\":\"task-001\"}");
      call.response(200, Map.of(), "{\"accepted\":true}");
      call.result("CONFIRMED");
    }
    assertThat(last().at("/requestHeaders/X-Review-Task/0").asText()).isEqualTo("task-001");
    assertThat(last().at("/requestHeaders/Idempotency-Key/0").asText()).isEqualTo("task-001");
    assertThat(last().at("/requestHeaders/X-Request-ID/0").asText()).isEqualTo("attempt-002");
    assertThat(messages()).doesNotContain("CALLBACK_SECRET");
    assertThat(events.list).hasSize(1);
  }

  @Test void signedLinksAndErrorEchoesAreSanitizedWithoutChangingTheInput() throws Exception {
    String body = "{\"fileUrl\":\"https://files.example.test/original.pdf?signature=SIGNED%2BSECRET&vendorKey=VENDOR_SECRET\","
        + "\"extension\":{\"X-Amz-Credential\":\"CREDENTIAL_SECRET\"}}";
    try (var call = ExternalInterfaceLog.start("DRAWING_PDF", "OUTBOUND", "POST", URI.create("http://example.test/verify"), Map.of())) {
      call.request("{\"drawingNo\":\"REAL-DRAWING\"}");
      call.response(200, Map.of(), body);
    }
    assertThat(messages()).contains("original.pdf", "REAL-DRAWING")
        .doesNotContain("SIGNED", "VENDOR_SECRET", "CREDENTIAL_SECRET");
    assertThat(body).contains("SIGNED%2BSECRET", "VENDOR_SECRET", "CREDENTIAL_SECRET");
  }

  private JsonNode last() throws Exception { return json.readTree(events.list.getLast().getFormattedMessage()); }
  private String messages() { return events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b); }

  @RestController static class Echo {
    String received;
    @PostMapping(value = "/integration/v1/workflow-events", produces = "application/json")
    String receive(HttpServletRequest request) throws IOException {
      received = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return "{\"code\":\"0\",\"requestId\":\"RAW-001\"}";
    }
  }
}
