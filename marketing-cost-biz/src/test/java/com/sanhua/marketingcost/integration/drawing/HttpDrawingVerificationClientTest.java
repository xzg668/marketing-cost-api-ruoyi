package com.sanhua.marketingcost.integration.drawing;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class HttpDrawingVerificationClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private final Logger logger = (Logger) LoggerFactory.getLogger(ExternalInterfaceLog.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>();
  private final DrawingVerificationProperties properties = new DrawingVerificationProperties();
  private final AtomicInteger calls = new AtomicInteger();
  private final CountDownLatch received = new CountDownLatch(1);
  private final AtomicReference<String> requestBody = new AtomicReference<>();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private final AtomicReference<String> requestUri = new AtomicReference<>();
  private final AtomicReference<String> requestMethod = new AtomicReference<>();
  private final AtomicReference<String> upstreamRequestId = new AtomicReference<>();
  private HttpServer server;
  private ExecutorService executor;
  private String responseBody;
  private int status = 200;
  private long headerDelay;
  private long bodyDelay;

  @BeforeEach void start() throws Exception {
    events.start(); logger.addAppender(events);
    responseBody = DrawingVerificationFixtures.initial("external-001").toString();
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
    server.createContext("/api/drawings/verify", this::respond); server.start();
    properties.setEnabled(true);
    properties.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    properties.setRequestTimeout(Duration.ofSeconds(5));
  }

  @AfterEach void stop(TestInfo info) throws Exception {
    server.stop(0); executor.shutdownNow();
    String evidence = System.getProperty("edp.evidence.dir");
    if (evidence != null) {
      Path folder = Path.of(evidence, "interface-logs"); Files.createDirectories(folder);
      Files.write(folder.resolve(info.getTestMethod().orElseThrow().getName() + "-" + status + ".jsonl"),
          events.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }
    logger.detachAppender(events); events.stop();
  }

  @Test void sendsOnlyActualDrawingAndPreservesFullSummaryRegardlessOfParseStatuses() throws Exception {
    var payload = client().verify(query());
    assertThat(calls).hasValue(1); assertThat(requestMethod.get()).isEqualTo("POST");
    assertThat(requestUri.get()).isEqualTo("/api/drawings/verify");
    assertThat(json.readTree(requestBody.get())).isEqualTo(json.readTree("{\"drawingNo\":\"YCQB05L89\"}"));
    assertThat(authorization.get()).isNull(); assertThat(upstreamRequestId.get()).isNull();
    assertThat(payload.rawJson()).isEqualTo(responseBody);
    assertThat(payload.summary().differenceCount()).isZero();
    assertThat(payload.summary().status()).isEqualTo("reviewRequired");
    assertThat(payload.summary().reviewReasons()).isNotEmpty();
    assertThat(payload.summary().data()).hasSize(2); assertThat(payload.summary().plmData()).hasSize(2);
    assertThat(events.list).hasSize(1);
    JsonNode event = last();
    assertThat(event.path("businessOutcome").asText()).isEqualTo("SUMMARY_RECEIVED");
    assertThat(event.path("localTaskId").asLong()).isEqualTo(41L);
    assertThat(event.path("requestId").asText()).isEqualTo("local-request-001");
    assertThat(event.path("externalTaskId").asText()).isEqualTo("external-001");
    assertThat(event.path("oaFormItemId").asLong()).isEqualTo(81L);
    assertThat(event.path("durationMs").asLong()).isNotNegative();
  }

  @Test void differencesReturnEmptyDataAndActualEditUrlForBusinessOrchestration() throws Exception {
    ObjectNode summary = (ObjectNode) json.readTree(responseBody);
    summary.put("differenceCount", 2); summary.set("data", json.createObjectNode());
    responseBody = summary.toString();
    var payload = client().verify(query());
    assertThat(payload.summary().differenceCount()).isEqualTo(2);
    assertThat(payload.summary().data()).isEmpty();
    assertThat(payload.summary().editUrl()).isEqualTo(summary.path("editUrl").asText());
    assertThat(payload.summary().plmData()).hasSize(2);
  }

  @ParameterizedTest @ValueSource(ints = {302, 400, 401, 404, 422, 500, 503})
  void keepsHttpFailuresAndDoesNotRetryOrFallBack(int httpStatus) throws Exception {
    status = httpStatus; responseBody = "{\"detail\":\"upstream error\",\"signature\":\"PRIVATE_SIGNATURE\"}";
    assertThatThrownBy(() -> client().verify(query())).isInstanceOfSatisfying(DrawingVerificationException.class, error -> {
      assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.HTTP_ERROR);
      assertThat(error.getHttpStatus()).isEqualTo(httpStatus); assertThat(error.getResponseBody()).isEqualTo(responseBody);
      assertThat(error.getMessage()).doesNotContain("PRIVATE_SIGNATURE");
    });
    assertThat(calls).hasValue(1);
    assertThat(last().path("httpStatus").asInt()).isEqualTo(httpStatus);
    assertThat(last().path("businessOutcome").asText()).isEqualTo("HTTP_ERROR");
    assertThat(messages()).doesNotContain("PRIVATE_SIGNATURE");
  }

  @Test void invalidJsonIsNotSuccessfulAndLogsCaptureReason() throws Exception {
    responseBody = "{\"signature\":\"BROKEN_PRIVATE";
    assertInvalidResponse();
    assertThat(last().at("/response/captureStatus").asText()).isEqualTo("NON_JSON_OR_INVALID_JSON");
    assertThat(messages()).doesNotContain("BROKEN_PRIVATE");
  }

  @Test void missingDifferenceCountNeverDefaultsToZero() throws Exception {
    ObjectNode summary = (ObjectNode) json.readTree(responseBody); summary.remove("differenceCount");
    responseBody = summary.toString(); assertInvalidResponse();
  }

  @Test void rejectsOldNodesAndOuterEnvelopeContracts() {
    responseBody = "{\"nodes\":[]}"; assertInvalidResponse();
    responseBody = "{\"code\":0,\"message\":\"ok\",\"data\":{}}"; assertInvalidResponse();
    assertThat(calls).hasValue(2);
  }

  @Test void timeoutBeforeHeadersHasAnExplicitMissingResponseAndNoRetry() throws Exception {
    headerDelay = 1500; properties.setRequestTimeout(Duration.ofMillis(250));
    assertThatThrownBy(() -> client().verify(query())).isInstanceOfSatisfying(DrawingVerificationException.class,
        error -> assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.TIMEOUT));
    assertThat(calls).hasValue(1);
    assertThat(last().at("/response/captureStatus").asText()).isEqualTo("TIMEOUT");
    assertThat(last().path("outcome").asText()).isEqualTo("FAILED");
  }

  @Test void totalTimeoutAlsoCoversAStalledResponseBody() throws Exception {
    bodyDelay = 1500; properties.setRequestTimeout(Duration.ofMillis(250));
    assertThatThrownBy(() -> client().verify(query())).isInstanceOfSatisfying(DrawingVerificationException.class,
        error -> assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.TIMEOUT));
    assertThat(calls).hasValue(1);
    assertThat(last().path("businessOutcome").asText()).isEqualTo("TIMEOUT");
  }

  @Test void interruptionIsPreservedAndTheSingleRequestIsCancelled() throws Exception {
    headerDelay = 1500;
    AtomicReference<Throwable> failure = new AtomicReference<>();
    AtomicReference<Boolean> interrupted = new AtomicReference<>();
    Thread caller = Thread.startVirtualThread(() -> {
      try { client().verify(query()); } catch (Throwable error) { failure.set(error); interrupted.set(Thread.currentThread().isInterrupted()); }
    });
    assertThat(received.await(3, TimeUnit.SECONDS)).isTrue(); caller.interrupt(); caller.join(3000);
    assertThat(caller.isAlive()).isFalse(); assertThat(interrupted.get()).isTrue();
    assertThat(failure.get()).isInstanceOfSatisfying(DrawingVerificationException.class,
        error -> assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.INTERRUPTED));
    assertThat(last().path("businessOutcome").asText()).isEqualTo("INTERRUPTED");
  }

  @Test void connectionFailureStaysAnErrorAndRetainsItsCause() throws Exception {
    server.stop(0);
    assertThatThrownBy(() -> client().verify(query())).isInstanceOfSatisfying(DrawingVerificationException.class, error -> {
      assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.TRANSPORT_ERROR);
      assertThat(error.getCause()).isNotNull();
    });
    assertThat(last().path("outcome").asText()).isEqualTo("FAILED");
  }

  @Test void oversizedLoggingDoesNotTruncateTheActualResponseEvidence() throws Exception {
    ObjectNode summary = (ObjectNode) json.readTree(responseBody);
    ((ObjectNode) summary.path("plm_data").get(0)).put("extendedNote", "x".repeat(ExternalInterfaceLog.MAX_BODY_BYTES + 1));
    responseBody = summary.toString();
    var payload = client().verify(query());
    assertThat(payload.rawJson()).isEqualTo(responseBody);
    assertThat(payload.summary().plmData().get(0).path("extendedNote").asText()).hasSize(ExternalInterfaceLog.MAX_BODY_BYTES + 1);
    assertThat(last().at("/response/captureStatus").asText()).isEqualTo("OVER_LIMIT");
    assertThat(last().at("/response/bytes").asInt()).isGreaterThan(ExternalInterfaceLog.MAX_BODY_BYTES);
    assertThat(last().path("externalTaskId").asText()).isEqualTo("external-001");
  }

  @Test void signedUrlsAreRedactedInLogsWhilePayloadRemainsOriginal() throws Exception {
    ObjectNode summary = (ObjectNode) json.readTree(responseBody);
    String signed = "https://files.example.test/original.pdf?X-Amz-Signature=SECRET_SIGN&vendorPermit=SECRET_VENDOR&expires=100";
    ((ObjectNode) summary.path("plm_data").get(0)).put("fileUrl", signed);
    summary.put("editUrl", "https://editor.example.test/edit/task?token=SECRET_EDIT");
    responseBody = summary.toString();
    var payload = client().verify(query());
    assertThat(payload.summary().plmData().get(0).path("fileUrl").asText()).isEqualTo(signed);
    assertThat(messages()).contains("original.pdf", "external-001", "[REDACTED]")
        .doesNotContain("SECRET_SIGN", "SECRET_VENDOR", "SECRET_EDIT");
  }

  @Test void disabledAndMissingDrawingCannotMakeAnyHttpRequest() {
    properties.setEnabled(false);
    assertThatThrownBy(() -> client().verify(query())).isInstanceOf(DrawingVerificationException.class);
    assertThatThrownBy(() -> client().verify(new DrawingVerificationClient.Query(" ", "r", null, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(calls).hasValue(0); assertThat(events.list).isEmpty();
  }

  private void assertInvalidResponse() {
    assertThatThrownBy(() -> client().verify(query())).isInstanceOfSatisfying(DrawingVerificationException.class, error -> {
      assertThat(error.getCode()).isEqualTo(DrawingVerificationException.Code.INVALID_RESPONSE);
      assertThat(error.getHttpStatus()).isEqualTo(200); assertThat(error.getResponseBody()).isEqualTo(responseBody);
    });
  }

  private HttpDrawingVerificationClient client() { return new HttpDrawingVerificationClient(properties, new DrawingVerificationCodec(json), json); }
  private DrawingVerificationClient.Query query() { return new DrawingVerificationClient.Query("YCQB05L89", "local-request-001", 41L, "OA-001", 81L, "2026-10"); }
  private JsonNode last() throws Exception { return json.readTree(events.list.getLast().getFormattedMessage()); }
  private String messages() { return events.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + b); }

  private void respond(HttpExchange exchange) throws IOException {
    calls.incrementAndGet(); requestMethod.set(exchange.getRequestMethod()); requestUri.set(exchange.getRequestURI().toString());
    requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
    upstreamRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-ID")); received.countDown();
    try {
      if (headerDelay > 0) Thread.sleep(headerDelay);
      byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(status, bytes.length);
      if (bodyDelay > 0) Thread.sleep(bodyDelay);
      exchange.getResponseBody().write(bytes);
    } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    finally { exchange.close(); }
  }
}
