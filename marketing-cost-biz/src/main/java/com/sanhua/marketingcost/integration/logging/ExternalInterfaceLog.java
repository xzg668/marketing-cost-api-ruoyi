package com.sanhua.marketingcost.integration.logging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** 独立的外部接口报文日志；不参与业务事务，不记录鉴权凭据或异常中的 SQL。 */
public final class ExternalInterfaceLog {
  public static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
  private static final Logger LOG = LoggerFactory.getLogger(ExternalInterfaceLog.class);
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
      .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private static final String REDACTED = "[REDACTED]";
  private static final Set<String> SENSITIVE = Set.of("authorization", "proxyauthorization", "cookie", "setcookie",
      "password", "passwd", "pwd", "credentials", "secret", "appkey", "apikey", "clientsecret", "privatekey",
      "token", "accesstoken", "acesstoken", "refreshtoken", "jwt", "authorizationcode",
      "signature", "sig", "credential", "awsaccesskeyid", "ossaccesskeyid", "authkey", "policy", "keypairid");
  private static final Set<String> SAFE_HEADERS = Set.of("content-type", "content-length", "x-request-id",
      "x-trace-id", "x-oa-call-id", "x-file-sha256", "x-drawing-no", "x-source", "x-review-task", "idempotency-key");
  private static final Pattern URL_IN_TEXT = Pattern.compile("(?i)https?://[^\\s\\\"<>]+");

  private ExternalInterfaceLog() {}

  public static Exchange start(String system, String direction, String method, URI uri,
      Map<String, List<String>> headers) {
    return new Exchange(system, direction, method, uri, headers);
  }

  public static final class Exchange implements AutoCloseable {
    private final Map<String, Object> event = new LinkedHashMap<>();
    private final List<String> secrets = new ArrayList<>();
    private final long started = System.nanoTime();
    private final boolean authentication;
    private String requestBody;
    private String requestState = "NOT_CAPTURED";
    private String responseBody;
    private String responseState = "NOT_RECEIVED";

    private Exchange(String system, String direction, String method, URI uri, Map<String, List<String>> headers) {
      String path = uri.getPath() == null ? "" : uri.getPath();
      authentication = path.contains("/oauth") || path.contains("/auth/oa/");
      event.put("event", "EXTERNAL_INTERFACE");
      event.put("startedAt", Instant.now().toString());
      event.put("callId", UUID.randomUUID().toString());
      event.put("traceId", MDC.get("traceId"));
      event.put("oaCallId", MDC.get("oaCallId"));
      event.put("system", system);
      event.put("direction", direction);
      event.put("method", method);
      // URI 查询串可能携带令牌；地址只保留主机与路径，参数单独脱敏。
      event.put("endpoint", (uri.getHost() == null ? "" : uri.getScheme() + "://" + uri.getHost()
          + (uri.getPort() < 0 ? "" : ":" + uri.getPort())) + path);
      event.put("query", query(uri.getRawQuery()));
      event.put("requestHeaders", headers(headers));
      event.put("outcome", "UNCONFIRMED");
    }

    public String id() { return (String) event.get("callId"); }

    public void correlate(String key, Object value) {
      if (value != null) event.put(key, value);
    }

    public void result(String result) { event.put("businessOutcome", result); }

    /** 同时遮蔽对端在错误文字中回显的本应用凭据。 */
    public void redactSecrets(String... values) {
      for (String value : values) remember(value);
    }

    public void request(String body) {
      requestBody = body;
      requestState = body == null ? "NO_BODY" : "CAPTURED";
    }

    public void requestUnavailable(String reason) { requestState = reason; }

    public void response(int status, Map<String, List<String>> headers, String body) {
      event.put("httpStatus", status);
      event.put("responseHeaders", headers(headers));
      if (!"FAILED".equals(event.get("outcome"))) event.put("outcome", status >= 400 ? "HTTP_ERROR" : "HTTP_RESPONSE");
      responseBody = body;
      responseState = body == null ? "NO_BODY" : "CAPTURED";
    }

    public void responseUnavailable(String reason) { responseState = reason; }

    public void failure(Throwable failure) {
      event.put("outcome", "FAILED");
      event.put("exceptionType", failure.getClass().getSimpleName());
      if (failure.getCause() != null) event.put("causeType", failure.getCause().getClass().getSimpleName());
    }

    private Map<String, Object> query(String raw) {
      Map<String, Object> result = new LinkedHashMap<>();
      if (raw == null) return result;
      for (String pair : raw.split("&")) {
        String[] parts = pair.split("=", 2);
        try {
          String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
          String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
          if (sensitive(key) || authentication && Set.of("code", "state").contains(key.toLowerCase(Locale.ROOT))) {
            remember(value);
            result.put(key, REDACTED);
          } else result.put(key, value);
        } catch (IllegalArgumentException invalidEncoding) {
          result.put("captureStatus", "INVALID_QUERY_ENCODING");
        }
      }
      return result;
    }

    private Map<String, List<String>> headers(Map<String, List<String>> source) {
      Map<String, List<String>> safe = new LinkedHashMap<>();
      source.forEach((name, values) -> {
        if (sensitive(name)) {
          for (String value : values) {
            remember(value);
            if (value.regionMatches(true, 0, "Bearer ", 0, 7)) remember(value.substring(7));
            if (value.regionMatches(true, 0, "Basic ", 0, 6)) remember(value.substring(6));
          }
        }
        if (SAFE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) safe.put(name, values);
      });
      return safe;
    }

    private Map<String, Object> payload(String body, String state) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("captureStatus", state);
      if (!"CAPTURED".equals(state) || body == null) return result;
      int bytes = body.getBytes(StandardCharsets.UTF_8).length;
      result.put("bytes", bytes);
      if (bytes > MAX_BODY_BYTES) {
        result.put("captureStatus", "OVER_LIMIT");
        return result;
      }
      if (body.isBlank()) {
        result.put("captureStatus", "EMPTY");
        return result;
      }
      try {
        JsonNode value = JSON.readTree(body);
        if (authentication) {
          // 免登/换令牌的 code 和嵌套 data 都可能是凭据；只留非敏感状态字段。
          ObjectNode status = JSON.createObjectNode();
          for (String field : List.of("errcode", "status", "expires_in", "expire")) {
            if (value.has(field) && value.get(field).asText().matches("-?\\d{1,12}")) status.set(field, value.get(field));
          }
          result.put("captureStatus", "AUTHENTICATION_STATUS_ONLY");
          result.put("json", status);
          return result;
        }
        collectSecrets(value);
        result.put("json", sanitize(value));
      } catch (JsonProcessingException invalidJson) {
        // 无法可靠解析字段时不输出可能包含凭据的残缺报文；保留语法位置供联调定位。
        result.put("captureStatus", "NON_JSON_OR_INVALID_JSON");
        if (invalidJson.getLocation() != null) {
          result.put("errorLine", invalidJson.getLocation().getLineNr());
          result.put("errorColumn", invalidJson.getLocation().getColumnNr());
        }
      }
      return result;
    }

    private void collectSecrets(JsonNode node) {
      if (node == null) return;
      if (node.isObject()) node.fields().forEachRemaining(entry -> {
        if (sensitive(entry.getKey()) || sensitivePairValue(node, entry.getKey())) {
          if (entry.getValue().isTextual()) remember(entry.getValue().asText());
        } else collectSecrets(entry.getValue());
      });
      else if (node.isArray()) node.forEach(this::collectSecrets);
      else if (node.isTextual()) {
        var urls = URL_IN_TEXT.matcher(node.asText());
        while (urls.find()) {
          try {
            URI uri = URI.create(urls.group());
            query(uri.getRawQuery()); // 收集签名，连同错误文字中的回显一起脱敏。
            remember(uri.getRawUserInfo());
          } catch (IllegalArgumentException ignored) {
            // 无法解析的链接由 redactUrls 整体隐藏。
          }
        }
      }
    }

    private JsonNode sanitize(JsonNode node) {
      if (node == null) return JSON.nullNode();
      if (node.isObject()) {
        ObjectNode copy = JSON.createObjectNode();
        node.fields().forEachRemaining(entry -> {
          String key = entry.getKey();
          if (sensitive(key) || sensitivePairValue(node, key)) copy.put(key, REDACTED);
          else copy.set(key, sanitize(entry.getValue()));
        });
        return copy;
      }
      if (node.isArray()) {
        var copy = JSON.createArrayNode();
        node.forEach(value -> copy.add(sanitize(value)));
        return copy;
      }
      return node.isTextual() ? JSON.getNodeFactory().textNode(redactText(node.asText())) : node;
    }

    private void remember(String value) {
      if (value != null && !value.isBlank() && !secrets.contains(value)) secrets.add(value);
    }

    private boolean sensitivePairValue(JsonNode object, String key) {
      if (!Set.of("value", "fieldValue").contains(key)) return false;
      for (String name : List.of("name", "key", "fieldName")) {
        if (object.path(name).isTextual() && sensitive(object.path(name).asText())) return true;
      }
      return false;
    }

    private String redactText(String text) {
      String safe = text;
      for (String secret : secrets) {
        safe = safe.replace(secret, REDACTED).replace(URLEncoder.encode(secret, StandardCharsets.UTF_8), REDACTED);
      }
      return redactUrls(safe).replaceAll("(?i)(Bearer\\s+)[A-Za-z0-9._~+/=-]+", "$1[REDACTED]")
          .replaceAll("(?i)((?:access_?token|acesstoken|refresh_?token|app_?secret|app_?key|client_?secret|password|authorization)[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&<>]+", "$1[REDACTED]");
    }

    /** 日志保留文件主机和路径；URL 查询串可能是任意厂商的下载授权，统一隐藏参数值。 */
    private String redactUrls(String text) {
      return URL_IN_TEXT.matcher(text).replaceAll(match -> {
        try {
          URI uri = URI.create(match.group());
          String safe = uri.getScheme() + "://" + uri.getHost()
              + (uri.getPort() < 0 ? "" : ":" + uri.getPort()) + uri.getRawPath();
          if (uri.getRawQuery() != null) {
            var parts = new ArrayList<String>();
            for (String pair : uri.getRawQuery().split("&")) {
              parts.add(pair.split("=", 2)[0] + "=" + REDACTED);
            }
            safe += "?" + String.join("&", parts);
          }
          if (uri.getRawFragment() != null) safe += "#" + REDACTED;
          return java.util.regex.Matcher.quoteReplacement(safe);
        } catch (IllegalArgumentException invalidUrl) {
          return REDACTED;
        }
      });
    }

    @Override public void close() {
      try {
        event.put("request", payload(requestBody, requestState));
        event.put("response", payload(responseBody, responseState));
        // 回执也可能回显请求凭据；在两端凭据收集完成后再统一清理所有文本。
        JsonNode safe = sanitize(JSON.valueToTree(event));
        ((ObjectNode) safe).put("durationMs", (System.nanoTime() - started) / 1_000_000);
        LOG.info(JSON.writeValueAsString(safe));
      } catch (RuntimeException | JsonProcessingException failure) {
        LOG.warn("External interface payload log unavailable: type={}", failure.getClass().getSimpleName());
      }
    }
  }

  private static boolean sensitive(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return SENSITIVE.contains(normalized) || normalized.endsWith("authorization") || normalized.endsWith("apikey") || normalized.endsWith("password")
        || normalized.endsWith("token") || normalized.endsWith("secret")
        || normalized.endsWith("signature") || normalized.endsWith("credential");
  }
}
