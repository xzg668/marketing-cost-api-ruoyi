package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class OaTechnicalSubmissionClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private final AtomicInteger authorizeCalls = new AtomicInteger();
  private final AtomicInteger tokenCalls = new AtomicInteger();
  private final AtomicInteger submitCalls = new AtomicInteger();
  private HttpServer server;
  private ExecutorService executor;
  private OaTechnicalSubmissionClient client;
  private final OaWorkflowProperties properties = new OaWorkflowProperties();
  private volatile JsonNode received;
  private volatile String method, query, caller, contentType;
  private volatile long delayMs;
  private volatile String response = "{\"message\":{\"errcode\":\"0\",\"errmsg\":\"success\",\"requestId\":\"0000123\"}}";

  @BeforeEach void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/openserver/oauth2/authorize", exchange -> {
      authorizeCalls.incrementAndGet();
      respond(exchange, "{\"errcode\":0,\"code\":\"LOCAL_AUTH_CODE\"}");
    });
    server.createContext("/openserver/oauth2/access_token", exchange -> {
      tokenCalls.incrementAndGet();
      exchange.getRequestBody().readAllBytes();
      respond(exchange, "{\"errcode\":0,\"accessToken\":\"LOCAL_TOKEN +&\",\"expires_in\":7200}");
    });
    server.createContext(OaWorkflowClient.SUBMIT_PATH, exchange -> {
      submitCalls.incrementAndGet();
      received = json.readTree(exchange.getRequestBody());
      method = exchange.getRequestMethod();
      query = exchange.getRequestURI().getRawQuery();
      caller = exchange.getRequestHeaders().getFirst("callSysCode");
      contentType = exchange.getRequestHeaders().getFirst("Content-Type");
      try {
        if (delayMs > 0) Thread.sleep(delayMs);
        respond(exchange, response);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        exchange.close();
      }
    });
    server.start();
    var auth = new OaAuthProperties();
    auth.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    auth.setCorpId("LOCAL_CORP");
    auth.setAppKey("LOCAL_KEY");
    auth.setAppSecret("LOCAL_SECRET");
    auth.setCallSysCode("LOCAL_QUOTE");
    var tokens = new OaAccessTokenProvider(auth, json);
    client = new OaTechnicalSubmissionClient(json, new OaWorkflowClient(properties, auth, tokens, json));
  }

  @AfterEach void stop() {
    server.stop(0);
    executor.shutdownNow();
  }

  @Test void previewUsesExistingRemarkGeneratorWithoutNetwork() {
    var body = client.preview(request());
    assertThat(body.path("remark").asText()).isEqualTo(
        "产品I03测试产品A：工资[直接人工1.25元/只，辅助人工0.3元/只]。产品I03测试产品B：净损失率[2%]。");
    assertThat(body.path("userid").asText()).isEqualTo("0012211470");
    assertThat(body.path("requestId").asText()).isEqualTo("0000123");
    assertThat(body.at("/formData/dataDetails/0/dataKey").asText()).isEqualTo("bjxtdz");
    assertThat(body.at("/formData/dataDetails/0/content").asText()).isEqualTo(request().reviewUrl());
    assertThat(authorizeCalls.get() + tokenCalls.get() + submitCalls.get()).isZero();
  }

  @Test void sendsTechnicianIdentityAndBothProductsThroughActualHttpAndPublicAuth() {
    var expected = client.preview(request());
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    assertThat(result.requestId()).isEqualTo("0000123");
    assertThat(received).isEqualTo(expected);
    assertThat(received.size()).isEqualTo(5);
    assertThat(received.at("/otherParams/src").asText()).isEqualTo("submit");
    assertThat(received.at("/formData/module").asText()).isEqualTo("workflow");
    assertThat(received.at("/formData/dataDetails").size()).isEqualTo(1);
    assertThat(method).isEqualTo("POST");
    assertThat(caller).isEqualTo("LOCAL_QUOTE");
    assertThat(contentType).contains("application/json", "UTF-8");
    assertThat(URLDecoder.decode(query, StandardCharsets.UTF_8)).isEqualTo("access_token=LOCAL_TOKEN +&&userType=JOB_NUM");
    assertThat(authorizeCalls.get()).isEqualTo(1);
    assertThat(tokenCalls.get()).isEqualTo(1);
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test void consecutiveExplicitCallsReuseThePublicTokenCache() {
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.SUCCESS);
    assertThat(authorizeCalls.get()).isEqualTo(1);
    assertThat(tokenCalls.get()).isEqualTo(1);
    assertThat(submitCalls.get()).isEqualTo(2);
  }

  @ParameterizedTest
  @CsvSource(value = {"200031|没有查询到对应人员信息", "1200302|requestId 数据错误", "1200387|没找到dataKey对应的字段id,dataKey=bjxtdz"}, delimiter = '|')
  void returnsNativeRejectionWithoutRepeatingTheWrite(String code, String message) {
    var error = json.createObjectNode().put("errcode", code).put("errmsg", message);
    response = "200031".equals(code) ? error.toString() : json.createObjectNode().set("message", error).toString();
    var result = client.submit(request());
    assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
    assertThat(result.errorCode()).isEqualTo(code);
    assertThat(result.message()).isEqualTo(message);
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @ParameterizedTest
  @ValueSource(strings = {"not-json", "{\"message\":{\"errcode\":0,\"requestId\":\"OTHER\"}}",
      "{\"code\":\"0\",\"data\":{\"status\":\"SUCCEEDED\"}}"})
  void missingOrDifferentNativeReceiptRemainsUnconfirmed(String body) {
    response = body;
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test void timeoutDoesNotAutomaticallyResubmit() {
    properties.setReadTimeoutMs(1000);
    delayMs = 1500;
    assertThat(client.submit(request()).status()).isEqualTo(OaWorkflowResult.Status.UNKNOWN);
    assertThat(submitCalls.get()).isEqualTo(1);
  }

  @Test void acceptsFullTwoHundredCharacterRemarkAndRejectsOverflowBeforeAuth() {
    String full = "补".repeat(200);
    var input = request();
    var atLimit = new OaTechnicalSubmissionClient.Request(input.requestId(), input.technicianEmployeeNo(), full, input.reviewUrl());
    assertThat(client.preview(atLimit).path("remark").asText()).isEqualTo(full);
    var overflow = new OaTechnicalSubmissionClient.Request(input.requestId(), input.technicianEmployeeNo(), full + "录", input.reviewUrl());
    assertThatThrownBy(() -> client.submit(overflow)).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("201").hasMessageContaining("不能截断");
    assertThat(authorizeCalls.get() + tokenCalls.get() + submitCalls.get()).isZero();
  }

  @Test void rejectsNullRequestBeforeAuth() {
    assertThatThrownBy(() -> client.submit(null)).isInstanceOf(IllegalArgumentException.class);
    assertThat(authorizeCalls.get() + submitCalls.get()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"requestId", "technician", "remark", "url"})
  void missingRequiredFieldsNeverReachOa(String field) {
    for (String missing : new String[] {null, "", "  "}) {
      var input = request();
      var invalid = new OaTechnicalSubmissionClient.Request(
          "requestId".equals(field) ? missing : input.requestId(),
          "technician".equals(field) ? missing : input.technicianEmployeeNo(),
          "remark".equals(field) ? missing : input.remark(),
          "url".equals(field) ? missing : input.reviewUrl());
      assertThatThrownBy(() -> client.submit(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(authorizeCalls.get() + tokenCalls.get() + submitCalls.get()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"/submissions/1", "javascript:alert(1)", "https://user:password@quote.example.test/view",
      "https://quote.example.test/view#code=ONE_TIME", "https://quote.example.test/has space", "http:///missing-host"})
  void invalidReviewAddressNeverReachesOa(String address) {
    var input = request();
    assertThatThrownBy(() -> client.submit(new OaTechnicalSubmissionClient.Request(
        input.requestId(), input.technicianEmployeeNo(), input.remark(), address)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("查看地址");
    assertThat(authorizeCalls.get() + submitCalls.get()).isZero();
  }

  @ParameterizedTest @ValueSource(strings = {"requestId", "technician"})
  void invalidIdentifiersNeverReachOa(String field) {
    var input = request();
    for (String bad : new String[] {"bad value", "bad\nvalue", "1".repeat(101)}) {
      assertThatThrownBy(() -> client.submit(new OaTechnicalSubmissionClient.Request(
          "requestId".equals(field) ? bad : input.requestId(),
          "technician".equals(field) ? bad : input.technicianEmployeeNo(), input.remark(), input.reviewUrl())))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThat(authorizeCalls.get() + submitCalls.get()).isZero();
  }

  private OaTechnicalSubmissionClient.Request request() {
    return new OaTechnicalSubmissionClient.Request("0000123", "0012211470", OaTechnicalSubmissionFixtures.remark(json),
        "https://quote.example.test/submissions/TS-001?document=0000123");
  }

  private static void respond(HttpExchange exchange, String body) throws IOException {
    try {
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes);
    } finally {
      exchange.close();
    }
  }
}
