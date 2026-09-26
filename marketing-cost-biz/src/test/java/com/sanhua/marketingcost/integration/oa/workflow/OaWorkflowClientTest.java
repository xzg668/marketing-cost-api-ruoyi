package com.sanhua.marketingcost.integration.oa.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthenticationException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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

  @BeforeEach void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext(OaWorkflowClient.SUBMIT_PATH, exchange -> {
      calls.incrementAndGet();
      requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      query = exchange.getRequestURI().getRawQuery();
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

  @Test void interfaceLogsRetainBusinessIdsAndErrorCodeWithoutCredentialsOrRemark() {
    var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    events.start(); logger.addAppender(events);
    try {
      response = "{\"message\":{\"errcode\":1200302,\"errmsg\":\"PRIVATE_SECRET access_token=HIDDEN_TOKEN\"}}";
      var result = client.submit(request().put("remark", "CONFIDENTIAL_ACTUAL_SALARY_AND_PRICE"));
      assertThat(result.status()).isEqualTo(OaWorkflowResult.Status.REJECTED);
      String logged = events.list.stream().map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
          .reduce("", (left, right) -> left + "\n" + right);
      assertThat(logged).contains("requestId=00123", "userid=0001", "errorCode=1200302", "durationMs=", result.callId())
          .doesNotContain("PRIVATE_SECRET", "HIDDEN_TOKEN", "TOKEN +&中文", "CONFIDENTIAL_ACTUAL_SALARY_AND_PRICE");
    } finally {
      logger.detachAppender(events); events.stop();
    }
  }

  private ObjectNode request() {
    ObjectNode body = json.createObjectNode().put("userid", "0001").put("requestId", "00123").put("remark", "需补录工资资料");
    body.putObject("otherParams").put("src", "submit");
    body.putObject("formData").put("module", "workflow");
    return body;
  }
}
