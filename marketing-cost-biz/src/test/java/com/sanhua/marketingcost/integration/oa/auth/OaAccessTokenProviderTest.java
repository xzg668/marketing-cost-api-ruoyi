package com.sanhua.marketingcost.integration.oa.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OaAccessTokenProviderTest {
  private HttpServer server;
  private OaAuthProperties properties;
  private OaAccessTokenProvider provider;
  private final MutableClock clock = new MutableClock();
  private final AtomicInteger codes = new AtomicInteger();
  private final AtomicInteger tokens = new AtomicInteger();
  private final List<Request> requests = new ArrayList<>();
  private final Set<String> consumedCodes = new HashSet<>();
  private String tokenBody;
  private int firstTokenStatus = 200;
  private boolean malformedFirstToken;
  private long tokenRequestSeconds;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/openserver/oauth2/authorize", exchange -> {
      capture(exchange);
      respond(exchange, 200, "{\"errcode\":\"0\",\"code\":\"CODE-" + codes.incrementAndGet() + "\"}");
    });
    server.createContext("/openserver/oauth2/access_token", exchange -> {
      Request request = capture(exchange);
      int count = tokens.incrementAndGet();
      String code = parameters(request.body()).get("code");
      if (!consumedCodes.add(code)) {
        respond(exchange, 200, "{\"errcode\":200004}");
        return;
      }
      clock.advance(tokenRequestSeconds);
      if (count == 1 && firstTokenStatus != 200) {
        respond(exchange, firstTokenStatus, "{}");
      } else if (count == 1 && malformedFirstToken) {
        respond(exchange, 200, "not-json SECRET-TOKEN");
      } else {
        respond(exchange, 200, tokenBody == null
            ? "{\"errcode\":0,\"accessToken\":\"TOKEN-" + count + "\",\"expires_in\":7200}"
            : tokenBody);
      }
    });
    server.start();
    properties = new OaAuthProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setCallSysCode("CALLER");
    properties.setCorpId("corp +&中文");
    properties.setAppKey("key +&中文");
    properties.setAppSecret("secret +&中文");
    properties.setMaxAttempts(2);
    provider = new OaAccessTokenProvider(properties, new ObjectMapper(), HttpClient.newHttpClient(), clock);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void sendsDocumentedHeadersAndEncodedParametersAndReusesToken() {
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    assertThat(codes.get()).isEqualTo(1);
    assertThat(tokens.get()).isEqualTo(1);
    assertThat(requests).hasSize(2).allSatisfy(request -> assertThat(request.caller()).isEqualTo("CALLER"));
    assertThat(requests.get(0).method()).isEqualTo("GET");
    assertThat(parameters(requests.get(0).query())).containsExactlyInAnyOrderEntriesOf(
        Map.of("corpid", properties.getCorpId(), "response_type", "code"));
    assertThat(requests.get(1).method()).isEqualTo("POST");
    assertThat(requests.get(1).contentType()).isEqualTo("application/x-www-form-urlencoded");
    assertThat(parameters(requests.get(1).body())).containsExactlyInAnyOrderEntriesOf(Map.of(
        "app_key", properties.getAppKey(), "app_secret", properties.getAppSecret(),
        "grant_type", "authorization_code", "code", "CODE-1"));
  }

  @Test
  void refreshesAtTwoMinuteMarginUsingRequestStartTime() {
    tokenRequestSeconds = 30;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    clock.advance(7049);
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    clock.advance(1);
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
    assertThat(codes.get()).isEqualTo(2);
  }

  @Test
  void concurrentCallersShareOneRefresh() throws Exception {
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    clock.advance(7200);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(12)) {
      List<Future<String>> futures = new ArrayList<>();
      for (int i = 0; i < 12; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          return provider.getAccessToken();
        }));
      }
      start.countDown();
      for (Future<String> future : futures) {
        assertThat(future.get(5, TimeUnit.SECONDS)).isEqualTo("TOKEN-2");
      }
    }
    assertThat(codes.get()).isEqualTo(2);
    assertThat(tokens.get()).isEqualTo(2);
  }

  @Test
  void lateRejectionOfOldTokenDoesNotInvalidateNewToken() {
    String old = provider.getAccessToken();
    provider.invalidateAccessToken(old);
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
    provider.invalidateAccessToken(old);
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
    assertThat(tokens.get()).isEqualTo(2);
  }

  @ParameterizedTest
  @ValueSource(ints = {404, 502, 503, 504})
  void retriesTokenExchangeWithANewSingleUseCode(int status) {
    firstTokenStatus = status;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
    assertThat(consumedCodes).containsExactlyInAnyOrder("CODE-1", "CODE-2");
    assertThat(codes.get()).isEqualTo(2);
  }

  @Test
  void responseLostAfterCodeConsumptionAlsoStartsWithANewCode() {
    malformedFirstToken = true;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
    assertThat(consumedCodes).containsExactlyInAnyOrder("CODE-1", "CODE-2");
  }

  @Test
  void doesNotRetryCredentialErrorsOrExposeResponseSecrets() {
    tokenBody = "{\"errcode\":200005,\"errmsg\":\"SECRET-TOKEN\"}";
    assertThatThrownBy(provider::getAccessToken).isInstanceOf(OaAuthenticationException.class)
        .hasMessageContaining("200005").hasMessageNotContaining("SECRET-TOKEN").hasNoCause();
    assertThat(codes.get()).isEqualTo(1);
    tokenBody = null;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
  }

  @Test
  void malformedJsonErrorDoesNotExposeTokensInCauseChain() {
    malformedFirstToken = true;
    properties.setMaxAttempts(1);
    assertThatThrownBy(provider::getAccessToken).hasMessageContaining("JK-02")
        .hasMessageNotContaining("SECRET-TOKEN").hasNoCause();
  }

  @ParameterizedTest
  @ValueSource(strings = {"null", "0", "-1", "\"7200\"", "1.5", "9223372036854775808"})
  void rejectsInvalidExpiryWithoutCaching(String expiry) {
    tokenBody = "{\"errcode\":0,\"accessToken\":\"SECRET-TOKEN\",\"expires_in\":" + expiry + "}";
    assertThatThrownBy(provider::getAccessToken).hasMessageContaining("expires_in")
        .hasMessageNotContaining("SECRET-TOKEN");
    tokenBody = null;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
  }

  @Test
  void acceptsDocumentedAlternateTokenFieldAndShortLifetime() {
    tokenBody = "{\"errcode\":0,\"acessToken\":\"ALTERNATE\",\"expires_in\":60}";
    assertThat(provider.getAccessToken()).isEqualTo("ALTERNATE");
    clock.advance(29);
    assertThat(provider.getAccessToken()).isEqualTo("ALTERNATE");
    assertThat(tokens.get()).isEqualTo(1);
    clock.advance(1);
    tokenBody = null;
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-2");
  }

  @Test
  void separateConfiguredProvidersDoNotShareCache() {
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
    var other = new OaAccessTokenProvider(properties, new ObjectMapper(), HttpClient.newHttpClient(), clock);
    assertThat(other.getAccessToken()).isEqualTo("TOKEN-2");
    assertThat(provider.getAccessToken()).isEqualTo("TOKEN-1");
  }

  private Request capture(HttpExchange exchange) throws IOException {
    Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().getRawQuery(),
        exchange.getRequestHeaders().getFirst("callSysCode"),
        exchange.getRequestHeaders().getFirst("Content-Type"),
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    requests.add(request);
    return request;
  }

  private static Map<String, String> parameters(String form) {
    Map<String, String> result = new LinkedHashMap<>();
    for (String field : form.split("&")) {
      String[] parts = field.split("=", 2);
      result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
          URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
    }
    return result;
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    try (var output = exchange.getResponseBody()) {
      output.write(bytes);
    }
  }

  private record Request(String method, String query, String caller, String contentType, String body) {}

  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-09-24T00:00:00Z");
    void advance(long seconds) { now = now.plusSeconds(seconds); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
  }
}
