package com.sanhua.marketingcost.integration.oa.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** OA 出站接口共用的进程内令牌管理；code 只在一次换取令牌的调用中使用。 */
@Component
public class OaAccessTokenProvider {
  private static final Set<Integer> RETRYABLE_STATUS = Set.of(404, 502, 503, 504);
  private static final long REFRESH_MARGIN_SECONDS = 120;

  private final OaAuthProperties properties;
  private final ObjectMapper json;
  private final HttpClient http;
  private final Clock clock;
  private CachedToken cached;

  @Autowired
  public OaAccessTokenProvider(OaAuthProperties properties, ObjectMapper json) {
    this(properties, json, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER).build(), Clock.systemUTC());
  }

  OaAccessTokenProvider(OaAuthProperties properties, ObjectMapper json, HttpClient http, Clock clock) {
    this.properties = properties;
    this.json = json;
    this.http = http;
    this.clock = clock;
  }

  /** 获取与更新共用同一把锁，避免并发调用重复申请令牌。 */
  public synchronized String getAccessToken() {
    properties.requireConfigured();
    if (cached != null && clock.instant().isBefore(cached.refreshAt)) {
      return cached.value;
    }
    cached = null;
    for (int attempt = 1; attempt <= properties.getMaxAttempts(); attempt++) {
      try {
        cached = exchangeToken(acquireCode(attempt), attempt);
        return cached.value;
      } catch (RetryableAuthenticationException exception) {
        if (attempt == properties.getMaxAttempts()) {
          throw new OaAuthenticationException(exception.getMessage());
        }
        // OA 的 code 只能使用一次。换取令牌的响应丢失时，也必须从新 code 开始重试。
        try {
          Thread.sleep(500L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new OaAuthenticationException("OA 鉴权重试被中断");
        }
      }
    }
    throw new OaAuthenticationException("OA 获取访问令牌失败");
  }

  /** 迟到的旧请求失败不能清除其他请求已更新的令牌。 */
  public synchronized void invalidateAccessToken(String rejectedToken) {
    try (var call = OaInterfaceLog.start("OA_TOKEN_INVALIDATE")) {
      boolean matched = cached != null && cached.value.equals(rejectedToken);
      if (matched) cached = null;
      call.field("cache", matched ? "CLEARED" : "STALE_RESPONSE_IGNORED");
      call.success();
    }
  }

  private String acquireCode(int attempt) {
    HttpRequest request = requestBuilder("/openserver/oauth2/authorize?corpid="
        + encode(properties.getCorpId()) + "&response_type=code").GET().build();
    JsonNode result = executeOnce(request, "JK-01", attempt);
    requireSuccess(result, "JK-01");
    String code = text(result, "code");
    if (code.isEmpty()) {
      throw new OaAuthenticationException("JK-01 未返回授权码");
    }
    return code;
  }

  private CachedToken exchangeToken(String code, int attempt) {
    String body = "app_key=" + encode(properties.getAppKey())
        + "&app_secret=" + encode(properties.getAppSecret())
        + "&grant_type=authorization_code&code=" + encode(code);
    HttpRequest request = requestBuilder("/openserver/oauth2/access_token")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    // 从请求开始计时，预留网络传输时间，避免把本地有效期算得比 OA 更长。
    Instant requestedAt = clock.instant();
    JsonNode result = executeOnce(request, "JK-02", attempt);
    requireSuccess(result, "JK-02");
    String token = text(result, "accessToken");
    if (token.isEmpty()) {
      token = text(result, "acessToken"); // OA V2.8 同时列出了该字段拼写。
    }
    if (token.isEmpty()) {
      throw new OaAuthenticationException("JK-02 未返回访问令牌");
    }
    JsonNode lifetime = result.path("expires_in");
    if (!lifetime.isIntegralNumber() || !lifetime.canConvertToLong() || lifetime.longValue() <= 0) {
      throw new OaAuthenticationException("JK-02 未返回有效的 expires_in");
    }
    long seconds = lifetime.longValue();
    long margin = Math.min(REFRESH_MARGIN_SECONDS, seconds / 2);
    final Instant refreshAt;
    try {
      refreshAt = requestedAt.plusSeconds(seconds - margin);
    } catch (DateTimeException | ArithmeticException exception) {
      throw new OaAuthenticationException("JK-02 expires_in 超出有效范围");
    }
    if (!clock.instant().isBefore(refreshAt)) {
      throw new OaAuthenticationException("JK-02 返回令牌剩余有效时间不足");
    }
    return new CachedToken(token, refreshAt);
  }

  private HttpRequest.Builder requestBuilder(String path) {
    return HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path))
        .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
        .header("callSysCode", properties.getCallSysCode())
        .header("Accept", "application/json");
  }

  private JsonNode executeOnce(HttpRequest request, String operation, int attempt) {
    try (var call = OaInterfaceLog.start(operation.equals("JK-01") ? "JK01_AUTHORIZE" : "JK02_ACCESS_TOKEN")) {
      call.field("direction", "OUTBOUND").field("method", request.method())
          .field("endpoint", request.uri().getPath()).field("attempt", attempt);
      try { return executeHttp(request, operation, call); }
      catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  private JsonNode executeHttp(HttpRequest request, String operation, OaInterfaceLog.Call call) {
    try {
      HttpResponse<String> response = http.send(request,
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      call.result("HTTP_RECEIVED", response.statusCode(), null);
      if (RETRYABLE_STATUS.contains(response.statusCode())) {
        throw new RetryableAuthenticationException(operation + " HTTP " + response.statusCode());
      }
      if (response.statusCode() != 200) {
        throw new OaAuthenticationException(operation + " HTTP " + response.statusCode());
      }
      JsonNode result = json.readTree(response.body());
      if (result == null || !result.isObject()) {
        throw new OaAuthenticationException(operation + " 返回的 JSON 格式不正确");
      }
      String code = result.path("errcode").asText();
      call.result("0".equals(code) ? "SUCCESS" : "REJECTED", response.statusCode(), code);
      return result;
    } catch (IOException exception) {
      // JSON 解析异常可能含响应中的 token；不把原始异常放入对外异常链。
      throw new RetryableAuthenticationException(operation + " 网络异常或返回非 JSON");
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new OaAuthenticationException(operation + " 被中断");
    }
  }

  private static void requireSuccess(JsonNode result, String operation) {
    String errorCode = result.path("errcode").asText("");
    if (!"0".equals(errorCode)) {
      String safeCode = errorCode.matches("-?\\d{1,10}") ? errorCode : "未知";
      throw new OaAuthenticationException(operation + " 返回失败状态，errcode=" + safeCode);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() ? value.textValue().trim() : "";
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static final class CachedToken {
    private final String value;
    private final Instant refreshAt;

    private CachedToken(String value, Instant refreshAt) {
      this.value = value;
      this.refreshAt = refreshAt;
    }
  }

  private static final class RetryableAuthenticationException extends OaAuthenticationException {
    private RetryableAuthenticationException(String message) {
      super(message);
    }
  }
}
