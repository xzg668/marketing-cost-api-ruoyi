package com.sanhua.marketingcost.integration.oa.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.integration.oa.auth.OaAccessTokenProvider;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthProperties;
import com.sanhua.marketingcost.integration.oa.auth.OaAuthenticationException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** OA 原生流程协议适配。写请求不自动重发，避免响应丢失后再次推进 OA 节点。 */
@Component
public class OaWorkflowClient {
  public static final String SUBMIT_PATH = "/openserver/api/workflow/core/paService/v1/submitRequest";
  private static final int REMARK_MAX_LENGTH = 200;

  /** OA V2.8 的意见字段上限；在准备事务内检查，避免冻结无法发送的资料。 */
  public static void validateRemark(String remark) {
    int length = remark.codePointCount(0, remark.length());
    if (length > REMARK_MAX_LENGTH) {
      throw new IllegalArgumentException("本次补录说明共 " + length + " 字符，超过 OA 文档的 "
          + REMARK_MAX_LENGTH + " 字符限制；请先确认 OA 支持的长度，不能截断后提交");
    }
  }
  private static final Logger log = LoggerFactory.getLogger(OaWorkflowClient.class);
  private final OaWorkflowProperties properties;
  private final OaAuthProperties auth;
  private final OaAccessTokenProvider tokens;
  private final ObjectMapper json;
  private final HttpClient http;

  @Autowired
  public OaWorkflowClient(OaWorkflowProperties properties, OaAuthProperties auth,
      OaAccessTokenProvider tokens, ObjectMapper json) {
    this(properties, auth, tokens, json, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(auth.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  OaWorkflowClient(OaWorkflowProperties properties, OaAuthProperties auth,
      OaAccessTokenProvider tokens, ObjectMapper json, HttpClient http) {
    this.properties = properties;
    this.auth = auth;
    this.tokens = tokens;
    this.json = json;
    this.http = http;
  }

  public OaWorkflowResult submit(ObjectNode request) {
    try (var call = OaInterfaceLog.start("OA_SUBMIT_REQUEST")) {
      call.field("direction", "OUTBOUND").field("method", "POST").field("endpoint", SUBMIT_PATH).business(request);
      if (request != null) call.field("remarkChars", request.path("remark").asText().length())
          .field("dataKey", request.at("/formData/dataDetails/0/dataKey").asText());
      try {
        var result = submitOnce(request, call.id());
        call.result(result.status().name(), result.httpStatus(), result.errorCode());
        return result;
      } catch (RuntimeException exception) {
        call.failure(exception);
        throw exception;
      }
    }
  }

  private OaWorkflowResult submitOnce(ObjectNode request, String callId) {
    if (request == null || !request.path("userid").isTextual() || request.path("userid").asText().isBlank()
        || !request.path("requestId").isTextual() || request.path("requestId").asText().isBlank()) {
      throw new IllegalArgumentException("OA 请求须提供文本类型的 userid 和原流程 requestId");
    }
    long started = System.nanoTime();
    // 先序列化为快照；发送期间调用方修改对象不能改变实际请求及核对依据。
    String body = request.toString();
    String expectedId = request.path("requestId").asText();
    final String token;
    final HttpRequest httpRequest;
    try {
      auth.requireConfigured();
      String baseUrl = properties.resolveBaseUrl(auth.getBaseUrl());
      token = tokens.getAccessToken();
      httpRequest = HttpRequest.newBuilder(URI.create(baseUrl + SUBMIT_PATH
              + "?access_token=" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "&userType=JOB_NUM"))
          .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
          .header("callSysCode", auth.getCallSysCode())
          .header("Content-Type", "application/json; charset=UTF-8")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    } catch (OaAuthenticationException | IllegalStateException | IllegalArgumentException exception) {
      return finish(callId, OaWorkflowResult.Status.NOT_SENT, null, "OA_AUTH_OR_CONFIG_ERROR",
          "未发送：OA 鉴权或接口配置失败，请核对公共鉴权与流程接口配置", null, null, started);
    }
    try {
      HttpResponse<String> response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = response.statusCode();
      JsonNode raw;
      try {
        raw = json.readTree(response.body());
      } catch (IOException exception) {
        raw = null;
      }
      JsonNode safe = safeResponse(raw, token);
      JsonNode nested = raw != null && raw.path("message").isObject() ? raw.path("message") : null;
      String topCode = errorCode(raw);
      String nestedCode = errorCode(nested);
      boolean tokenRejected = status == 401 || "200007".equals(topCode) || "200007".equals(nestedCode);
      if (tokenRejected) tokens.invalidateAccessToken(token);
      if (status == 401 || status == 403) {
        return finish(callId, OaWorkflowResult.Status.REJECTED, status, "OA_HTTP_" + status,
            "OA 拒绝鉴权或操作权限；未自动重发", null, safe, started);
      }
      if (status < 200 || status >= 300 || raw == null || !raw.isObject()) {
        return finish(callId, OaWorkflowResult.Status.UNKNOWN, status, "OA_RESPONSE_UNCONFIRMED",
            "OA 返回非成功 HTTP 状态或非 JSON 回执，请核实流程结果后再操作", null, safe, started);
      }
      if (raw.has("errcode") && topCode == null
          || nested != null && nested.has("errcode") && nestedCode == null) {
        return finish(callId, OaWorkflowResult.Status.UNKNOWN, status, "OA_INVALID_CODE",
            "OA 回执状态码格式异常，请核实原流程", null, safe, started);
      }
      if (topCode != null && nestedCode != null && !topCode.equals(nestedCode)) {
        return finish(callId, OaWorkflowResult.Status.UNKNOWN, status, "OA_CONFLICTING_CODES",
            "OA 顶层与内层状态不一致，请核实原流程", null, safe, started);
      }
      String code = nestedCode != null ? nestedCode : topCode;
      JsonNode result = nestedCode != null ? nested : raw;
      String id = text(result, "requestId");
      if (code != null && !"0".equals(code)) {
        String message = text(result, "errmsg");
        return finish(callId, OaWorkflowResult.Status.REJECTED, status, code,
            message == null ? "OA 拒绝本次办理" : redact(message, token), id == null ? null : redact(id, token), safe, started);
      }
      if (!"0".equals(code) || !expectedId.equals(id)) {
        return finish(callId, OaWorkflowResult.Status.UNKNOWN, status, "OA_RECEIPT_MISMATCH",
            "OA 回执缺少成功状态或匹配的原流程 requestId，请核实原流程", id == null ? null : redact(id, token), safe, started);
      }
      return finish(callId, OaWorkflowResult.Status.SUCCESS, status, code,
          "OA 已确认本次流程接口办理", id, safe, started);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return finish(callId, OaWorkflowResult.Status.UNKNOWN, null, "OA_INTERRUPTED",
          "发送被中断，OA 可能已办理，请核实原流程；未自动重发", null, null, started);
    } catch (IOException exception) {
      // 异常链可能含带 access_token 的 URL，不输出原始异常；超时不能推断 OA 未执行。
      return finish(callId, OaWorkflowResult.Status.UNKNOWN, null, "OA_IO_UNCONFIRMED",
          "连接异常或响应超时，OA 可能已办理，请核实原流程；未自动重发", null, null, started);
    }
  }

  private OaWorkflowResult finish(String callId, OaWorkflowResult.Status status, Integer httpStatus,
      String code, String message, String requestId, JsonNode response, long started) {
    var result = new OaWorkflowResult(callId, status, httpStatus, code, message, requestId, response,
        Duration.ofNanos(System.nanoTime() - started).toMillis());
    (status == OaWorkflowResult.Status.SUCCESS ? log.atInfo() : log.atWarn())
        .log("OA submitRequest result callId={} status={} httpStatus={} code={} response={}",
        callId, status, httpStatus, code, response);
    return result;
  }

  /** 只保留文档中的回执字段，避免异常回显中的 URL、凭证或未知敏感字段外泄。 */
  private JsonNode safeResponse(JsonNode raw, String token) {
    if (raw == null || !raw.isObject()) return null;
    ObjectNode safe = json.createObjectNode();
    for (String field : List.of("errcode", "errmsg", "requestId")) {
      String value = text(raw, field);
      if (value != null) safe.put(field, redact(value, token));
    }
    if (raw.path("message").isObject()) safe.set("message", safeResponse(raw.path("message"), token));
    return safe;
  }

  private String redact(String value, String token) {
    String safe = value;
    for (String secret : new String[] {token, auth.getAppSecret(), auth.getAppKey(), auth.getCorpId()}) {
      if (secret != null && !secret.isBlank()) {
        safe = safe.replace(secret, "[REDACTED]")
            .replace(URLEncoder.encode(secret, StandardCharsets.UTF_8), "[REDACTED]");
      }
    }
    safe = safe.replaceAll("(?i)(\\b(?:access_?token|acessToken|refresh_?token|app_?secret|app_?key|authorization)\\b[\\\"']?\\s*[:=]\\s*[\\\"']?)[^\\s\\\"'&<>]+", "$1[REDACTED]");
    return safe.length() > 20000 ? safe.substring(0, 20000) + "[TRUNCATED]" : safe;
  }

  private static String text(JsonNode node, String field) {
    if (node == null) return null;
    JsonNode value = node.path(field);
    return value.isTextual() || value.isIntegralNumber() ? value.asText() : null;
  }

  private static String errorCode(JsonNode node) {
    String code = text(node, "errcode");
    return code != null && code.matches("-?\\d{1,10}") ? code : null;
  }
}
