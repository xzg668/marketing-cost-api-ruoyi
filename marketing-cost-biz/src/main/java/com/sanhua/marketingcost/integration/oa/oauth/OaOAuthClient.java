package com.sanhua.marketingcost.integration.oa.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** 按 E10 文档使用查询参数；一次性 code 不自动重试，个人 token 不共享缓存。 */
@Component
public class OaOAuthClient {
  private static final String PREFIX = "/papi/sso/oauth2.0/";
  private final OaOAuthProperties properties;
  private final ObjectMapper json;
  private final HttpClient http;

  public record Identity(String employeeNo, String name, long expiresIn) {}

  public OaOAuthClient(OaOAuthProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  public String authorizeUrl(String state) {
    properties.requireConfigured();
    return base() + PREFIX + "authorize?response_type=code&client_id=" + encode(properties.getClientId())
        + "&redirect_uri=" + encode(properties.getRedirectUri()) + "&state=" + encode(state);
  }

  public Identity authenticate(String code) {
    properties.requireConfigured();
    if (code == null || code.isBlank() || code.length() > 2048) {
      throw new OaOAuthException("INVALID_CODE", "缺少有效的 OA 授权码，请重新进入");
    }
    JsonNode grant = post("accessToken", "grant_type=authorization_code&client_id=" + encode(properties.getClientId())
        + "&client_secret=" + encode(properties.getClientSecret()) + "&code=" + encode(code)
        + "&redirect_uri=" + encode(properties.getRedirectUri()));
    String token = text(grant.get("access_token"));
    long expires = grant.path("expire").asLong(0);
    if (token == null || expires <= 0) throw invalidReply();
    JsonNode profile = post("profile", "access_token=" + encode(token));
    // 已确认本应用 id 就是工号；不猜测 job_num、手机号或姓名，保留前导零。
    String employeeNo = text(profile.get("id"));
    if (employeeNo == null || !employeeNo.matches("[A-Za-z0-9._-]{1,64}")) {
      throw new OaOAuthException("INVALID_EMPLOYEE_NO", "OA 用户信息未返回有效工号 id");
    }
    String name = text(profile.path("attributes").get("username"));
    return new Identity(employeeNo, name == null ? employeeNo : name.substring(0, Math.min(64, name.length())),
        Math.min(expires, properties.getSessionSeconds()));
  }

  private JsonNode post(String operation, String query) {
    try (var call = OaInterfaceLog.start("OAUTH_" + operation.toUpperCase(java.util.Locale.ROOT))) {
      call.field("direction", "OUTBOUND").field("method", "POST").field("endpoint", PREFIX + operation);
      try {
        var request = HttpRequest.newBuilder(URI.create(base() + PREFIX + operation + "?" + query))
            .timeout(Duration.ofMillis(properties.getReadTimeoutMs())).header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
          call.result("REJECTED", response.statusCode(), "HTTP_ERROR");
          throw new OaOAuthException("HTTP_ERROR", "OA 免登接口请求失败，请重新进入或联系管理员");
        }
        var body = json.readTree(response.body());
        if (body == null || !body.isObject()) throw invalidReply();
        String resultCode = body.path("code").asText("");
        if (!"0".equals(resultCode) || body.path("status").asInt(0) != 200) {
          String safeCode = resultCode.matches("[0-9]{1,12}") ? resultCode : "INVALID_RESPONSE";
          call.result("REJECTED", response.statusCode(), safeCode);
          throw new OaOAuthException(safeCode, "OA 免登校验失败（" + safeCode + "），请重新进入");
        }
        call.result("SUCCESS", response.statusCode(), "0");
        return body;
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        call.result("FAILED", null, "INTERRUPTED");
        throw new OaOAuthException("INTERRUPTED", "OA 免登请求中断，请重新进入");
      } catch (IOException | IllegalArgumentException exception) {
        call.result("FAILED", null, "CONNECTION_OR_RESPONSE_ERROR");
        throw new OaOAuthException("CONNECTION_OR_RESPONSE_ERROR", "OA 免登连接或回执异常，请重新进入");
      }
    }
  }

  private String base() { return properties.getBaseUrl().replaceAll("/+$", ""); }
  private static String text(JsonNode node) {
    return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText().trim() : null;
  }
  private static OaOAuthException invalidReply() {
    return new OaOAuthException("INVALID_RESPONSE", "OA 免登返回内容不完整，请联系管理员");
  }
  public static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
