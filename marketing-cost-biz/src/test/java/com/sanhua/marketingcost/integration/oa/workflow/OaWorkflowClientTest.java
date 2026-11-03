package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthenticationException;
import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OaWorkflowClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private final OaAccessTokenProvider tokens = mock(OaAccessTokenProvider.class);
  private final OaAuthProperties auth = new OaAuthProperties();
  private final OaWorkflowProperties properties = new OaWorkflowProperties();
  private final AtomicInteger calls = new AtomicInteger();
  private HttpServer server;
  private ExecutorService executor;
  private OaWorkflowClient client;
  private volatile int status = 200;
  private volatile String response = "{\"message\":{\"errcode\":\"0\",\"errmsg\":\"success\",\"requestId\":\"00123\"}}";
  private volatile long delay;
  private volatile String requestBody;
  private volatile String query;
  private volatile String method;
  private volatile String caller;
  private volatile String contentType;
  private volatile String path;

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/", exchange -> {
      calls.incrementAndGet();
      requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      query = exchange.getRequestURI().getRawQuery();
      path = exchange.getRequestURI().getPath();
      method = exchange.getRequestMethod();
      caller = exchange.getRequestHeaders().getFirst("callSysCode");
      contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      try {
        if (delay > 0) Thread.sleep(delay);
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
      finally { exchange.close(); }
    });
    server.start();
    auth.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    auth.setCallSysCode("CALLER");
    auth.setCorpId("PRIVATE_CORP");
    auth.setAppKey("PRIVATE_KEY");
    auth.setAppSecret("PRIVATE_SECRET");
    when(tokens.getAccessToken()).thenReturn("TOKEN +&中文");
    client = new OaWorkflowClient(properties, auth, tokens, json);
  }

  @AfterEach void stop() {
    server.stop(0);
    executor.shutdownNow();
    Thread.interrupted();
  }

  @Test void rejectionUsesItsOwnBaseUrlAndNativeBodyWithSharedAuthentication() throws Exception {
    properties.setBaseUrl("http://127.0.0.1:1/unreachable-submit");
    properties.setRejectBaseUrl(auth.getBaseUrl());
    var body = json.createObjectNode().put("userid", "0001").put("requestId", "00123")
        .put("RejectToType", "0").put("RejectToNodeid", "000987654321");
    assertThat(client.reject(body).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    assertThat(path).isEqualTo(OaWorkflowClient.REJECT_PATH);
    assertThat(json.readTree(requestBody)).isEqualTo(body);
    assertThat(caller).isEqualTo("CALLER");
    assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("access_token=TOKEN +&中文&userType=JOB_NUM");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void invalidRejectionNodeIsNeverSentAndOaRejectionIsNotRetried() {
    var body = json.createObjectNode().put("userid", "0001").put("requestId", "00123").put("RejectToType", "0");
    assertThatThrownBy(() -> client.reject(body)).hasMessageContaining("RejectToNodeid");
    assertThat(calls.get()).isZero();
    response = "{\"message\":{\"errcode\":\"1200308\",\"errmsg\":\"退回节点不在可选范围内\"}}";
    var result = client.reject(body.put("RejectToNodeid", "000987654321"));
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
    assertThat(result.errorCode()).isEqualTo("1200308");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void legacySavedRejectionIsSentWithOnlyTheCorrectedField() throws Exception {
    var saved = json.createObjectNode().put("userid", "0001").put("requestId", "00123")
        .put("RejectToType", "0").put("rejectNodeid", "000987654321");
    var adapter = new OaTechnicalReturnClient(json, client, properties);
    assertThat(adapter.reject(saved).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    var sent = json.readTree(requestBody);
    assertThat(sent.path("RejectToNodeid").asText()).isEqualTo("000987654321");
    assertThat(sent.has("rejectNodeid")).isFalse();
    assertThat(saved.has("RejectToNodeid")).isFalse();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void rejectionTimeoutRemainsUnknownAndDoesNotResend() {
    delay = 1500;
    properties.setReadTimeoutMs(1000);
    var body = json.createObjectNode().put("userid", "0001").put("requestId", "00123")
        .put("RejectToType", "0").put("RejectToNodeid", "000987654321");
    assertThat(client.reject(body).status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void sendsNativeRequestWithSharedTokenAndUtf8() throws Exception {
    var request = request();
    var result = client.submit(request);
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    assertThat(result.requestId()).isEqualTo("00123");
    assertThat(json.readTree(requestBody)).isEqualTo(request);
    assertThat(method).isEqualTo("POST");
    assertThat(caller).isEqualTo("CALLER");
    assertThat(contentType).contains("application/json", "UTF-8");
    assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("access_token=TOKEN +&中文&userType=JOB_NUM");
    assertThat(calls.get()).isEqualTo(1);
    verify(tokens).getAccessToken();
  }

  @ParameterizedTest
  @CsvSource({"FI-SC-005,jsy", "FI-SC-006,jsy", "FI-SC-020,jsy", "FI-SR-005,FI-SR-005"})
  void dispatchSendsConfirmedPeopleFieldForEachProcess(String process, String expectedField) throws Exception {
    // 006/020 沿用已联调通过的外部配置；家用字段使用新增默认映射。
    properties.getTechnicalPeopleDataKeys().put("FI-SC-006", "jsy");
    properties.getTechnicalPeopleDataKeys().put("FI-SC-020", "jsy");
    try (var validation = Validation.buildDefaultValidatorFactory()) {
      var builder = new OaTechnicalDispatchRequestBuilder(json, validation.getValidator(), properties);
      var assignment = new OaTechnicalDispatchRequest.Assignment("0002", "张三", List.of("SALARY"));
      var product = new OaTechnicalDispatchRequest.Product("ROW-1", "P1", List.of(assignment));
      var command = new OaTechnicalDispatchRequest("00123", process, null, List.of(product));
      var body = builder.build(command, "0001");

      assertThat(client.submit(body).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
      var sent = json.readTree(requestBody);
      assertThat(sent.at("/formData/dataDetails/0/dataKey").asText()).isEqualTo(expectedField);
      assertThat(sent.at("/formData/dataDetails/0/dataOptions/0/optionId").asText()).isEqualTo("0002");
      assertThat(sent.path("requestId").asText()).isEqualTo("00123");
      assertThat(sent.path("userid").asText()).isEqualTo("0001");
      assertThat(sent.path("remark").asText()).isEqualTo("技术补录：产品P1：张三（0002）补工资。");
      assertThat(sent).isEqualTo(body);
      assertThat(path).isEqualTo(OaWorkflowClient.SUBMIT_PATH);
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest @ValueSource(strings = {
      "{\"errcode\":\"200031\",\"errmsg\":\"没有查询到对应人员信息\"}",
      "{\"message\":{\"errcode\":1200387,\"errmsg\":\"没找到dataKey对应的字段id\"}}"})
  void recognizesTopLevelAndNestedBusinessRejectionsWithoutRetry(String body) {
    response = body;
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
    assertThat(result.message()).isNotBlank();
    assertThat(calls.get()).isEqualTo(1);
    verify(tokens, never()).invalidateAccessToken(any());
  }

  @ParameterizedTest
  @CsvSource({"新品,1328371833677696778", "衍生品,1328371833677696778", "批量品,1328371833677696777"})
  void householdFinalCostHttpRequestContainsBothFieldsForEveryOriginalRow(String businessType, String tableId)
      throws Exception {
    properties.getFinalCostSubFormIds().put("FI-SR-005_NEW", "1328371833677696778");
    properties.getFinalCostSubFormIds().put("FI-SR-005_MASS", "1328371833677696777");
    var adapter = new OaFinalCostSubmissionClient(json, client, properties);
    var body = adapter.preview("00123", "0001", "FI-SR-005", List.of(
        new OaFinalCostSubmissionClient.Row(7, "292.15974237", "1.2345", businessType),
        new OaFinalCostSubmissionClient.Row(3, "151.57419573", null, businessType)));
    assertThat(adapter.submit(body).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    var sent = json.readTree(requestBody);
    var details = sent.at("/formData/dataDetails");
    assertThat(details).hasSize(4);
    for (var detail : details) {
      assertThat(detail.path("subFormId").isIntegralNumber()).isTrue();
      assertThat(detail.path("subFormId").asText()).isEqualTo(tableId);
    }
    assertThat(details.get(0).path("dataKey").asText()).isEqualTo("zcbbhs");
    assertThat(details.get(1).path("dataKey").asText()).isEqualTo("hysfzcbbhs");
    assertThat(details.get(0).path("content").asText()).isEqualTo("292.15974237");
    assertThat(details.get(1).path("content").asText()).isEqualTo("293.39424237");
    assertThat(details.get(2).path("content").asText()).isEqualTo("151.57419573");
    assertThat(details.get(3).path("content").asText()).isEqualTo("151.57419573");
    assertThat(sent).isEqualTo(body);
    assertThat(path).isEqualTo(OaWorkflowClient.SUBMIT_PATH);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings = {"{\"errcode\":200007}", "{\"message\":{\"errcode\":\"200007\"}}"})
  void expiredTokenIsInvalidatedButWriteIsNotReplayed(String body) {
    response = body;
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
    verify(tokens).invalidateAccessToken("TOKEN +&中文");
    verify(tokens, times(1)).getAccessToken();
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(ints = {401, 403})
  void httpAuthFailureIsRejectedWithoutRepeatingWrite(int httpStatus) {
    status = httpStatus; response = "not-json";
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(ints = {302, 404, 500, 502, 503, 504})
  void uncertainHttpResultDoesNotTriggerRedirectOrRetry(int httpStatus) {
    status = httpStatus;
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(calls.get()).isEqualTo(1);
  }

  @ParameterizedTest @ValueSource(strings = {"not-json TOKEN +&中文", "null", "[]", "{}",
      "{\"message\":{\"errcode\":0}}", "{\"message\":{\"errcode\":0,\"requestId\":\"OTHER\"}}",
      "{\"errcode\":\"bad\",\"message\":{\"errcode\":0,\"requestId\":\"00123\"}}",
      "{\"errcode\":0,\"requestId\":\"00123\",\"message\":{\"errcode\":null}}",
      "{\"errcode\":200031,\"message\":{\"errcode\":0,\"requestId\":\"00123\"}}"})
  void incompleteOrConflictingReceiptIsNeverSuccess(String body) throws Exception {
    response = body;
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(json.writeValueAsString(result)).doesNotContain("TOKEN +&中文");
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void readTimeoutIsUnknownAndOneWriteOnly() {
    delay = 1500;
    properties.setReadTimeoutMs(1000);
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test void authFailureAndMissingConfigAreNotSent() {
    when(tokens.getAccessToken()).thenThrow(new OaAuthenticationException("PRIVATE_SECRET"));
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.NOT_SENT);
    assertThat(result.message()).doesNotContain("PRIVATE_SECRET");
    auth.setAppSecret(null);
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.NOT_SENT);
    assertThat(calls.get()).isZero();
  }

  @Test void secretsInReceiptAndUnexpectedFieldsAreRemoved() throws Exception {
    response = """
        {"accessToken":"UNEXPECTED_SECRET","message":{"errcode":200031,
          "errmsg":"TOKEN +&中文 PRIVATE_SECRET PRIVATE_KEY access_token=OTHER_TOKEN&app_secret=OTHER_SECRET",
          "requestId":"PRIVATE_SECRET","other":{"accessToken":"HIDDEN"}}}
        """;
    String result = json.writeValueAsString(client.submit(request()));
    assertThat(result).contains("[REDACTED]").doesNotContain("TOKEN +&中文", "PRIVATE_SECRET", "PRIVATE_KEY",
        "UNEXPECTED_SECRET", "OTHER_TOKEN", "OTHER_SECRET", "HIDDEN");
  }

  @Test void interruptedCallPreservesThreadFlagAndDoesNotLeakUrl() throws Exception {
    HttpClient interrupted = mock(HttpClient.class);
    when(interrupted.send(any(), any())).thenThrow(new InterruptedException("access_token=PRIVATE_SECRET"));
    client = new OaWorkflowClient(properties, auth, tokens, json, interrupted);
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    assertThat(result.message()).doesNotContain("PRIVATE_SECRET");
  }

  @Test void numericSuccessCodeIsAcceptedWhenOriginalIdMatches() {
    response = "{\"message\":{\"errcode\":0,\"requestId\":\"00123\"}}";
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
  }

  @Test void interfaceSummaryOmitsRemarkWhileDedicatedPayloadLogKeepsItWithoutCredentials() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var payloadLogger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(
        com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog.class);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start(); logger.addAppender(events); payloadLogger.addAppender(events);
    try {
      response = "{\"message\":{\"errcode\":1200302,\"errmsg\":\"PRIVATE_SECRET access_token=HIDDEN_TOKEN\"}}";
      var result = client.submit(request().put("remark", "CONFIDENTIAL_ACTUAL_SALARY_AND_PRICE"));
      assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
      String logged = events.list.stream()
          .filter(event -> !event.getLoggerName().equals("com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog"))
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .reduce("", (left, right) -> left + "\n" + right);
      assertThat(logged).contains("requestId=00123", "userid=0001", "errorCode=1200302", "durationMs=", result.callId())
          .doesNotContain("PRIVATE_SECRET", "HIDDEN_TOKEN", "TOKEN +&中文", "CONFIDENTIAL_ACTUAL_SALARY_AND_PRICE");
      String payload = events.list.stream()
          .filter(event -> event.getLoggerName().equals("com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog"))
          .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage).reduce("", String::concat);
      assertThat(payload).contains("CONFIDENTIAL_ACTUAL_SALARY_AND_PRICE", "1200302", result.callId())
          .doesNotContain("PRIVATE_SECRET", "HIDDEN_TOKEN", "TOKEN +&中文");
    } finally {
      logger.detachAppender(events); payloadLogger.detachAppender(events); events.stop();
    }
  }

  private ObjectNode request() {
    ObjectNode body = json.createObjectNode().put("userid", "0001").put("requestId", "00123").put("remark", "需补录工资资料");
    body.putObject("otherParams").put("src", "submit");
    body.putObject("formData").put("module", "workflow");
    return body;
  }
}
