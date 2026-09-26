package com.sanhua.marketingcost.integration.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaInterfaceLog;
import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaPeer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** 目前唯一可用的是明确标记的 Python MOCK；REAL 未提供协议时不回退到模拟。 */
@Component
public class HttpTechnicalDataOaGateway implements TechnicalDataOaGateway {
  private final OaIntegrationProperties properties;
  private final ObjectMapper mapper;
  private final OaMessageCodec codec;
  private final HttpClient http;

  public HttpTechnicalDataOaGateway(OaIntegrationProperties properties, ObjectMapper mapper, OaMessageCodec codec) {
    this.properties = properties; this.mapper = mapper; this.codec = codec;
    http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getOutbound().getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER).build();
  }

  @Override public boolean enabled() {
    return properties.getMode() == OaIntegrationProperties.Mode.MOCK && properties.getOutbound().isEnabled();
  }

  @Override public OaPeer peer() {
    if (!enabled()) throw OaIntegrationException.conflict("OA_OUTBOUND_DISABLED", "OA 身份兑换通道未启用");
    var client = properties.getClients().get(properties.getOutbound().getClientId());
    return new OaPeer(client.getSourceSystem(), client.getEnvironment(), client.getBusinessUnits());
  }

  @Override public Identity exchange(long taskId, String code) {
    try (var call = OaInterfaceLog.start("OA_IDENTITY_EXCHANGE")) {
      call.field("taskId", taskId).field("mode", properties.getMode());
      try {
        var identity = exchangeIdentity(taskId, code);
        call.success();
        return identity;
      } catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  private Identity exchangeIdentity(long taskId, String code) {
    var reply = request("POST", "/identity/exchange", command(Map.of("taskId", taskId, "code", code, "audience", "quote-workbench")));
    requireSuccess(reply, "OA_IDENTITY_REJECTED", "OA 身份码无效、过期或已使用");
    var identity = reply.body();
    if (!identity.path("taskId").canConvertToLong() || identity.path("taskId").longValue() != taskId
        || !identity.path("externalUserId").isTextual() || identity.path("externalUserId").asText().isBlank()) {
      throw unknown("OA 身份回执缺少匹配的任务和人员");
    }
    return new Identity(identity.path("externalUserId").asText(), taskId);
  }

  private Reply request(String method, String path, String body) {
    try (var call = OaInterfaceLog.start("OA_GATEWAY_HTTP")) {
      call.field("direction", "OUTBOUND").field("method", method).field("endpoint", path)
          .field("mode", properties.getMode()).field("environment", properties.getEnvironment());
      try {
        var reply = executeRequest(method, path, body, call);
        call.result(reply.status() < 400 ? "HTTP_COMPLETED" : "REJECTED", reply.status(), reply.body().path("code").asText(null));
        return reply;
      } catch (RuntimeException exception) { call.failure(exception); throw exception; }
    }
  }

  private Reply executeRequest(String method, String path, String body, OaInterfaceLog.Call call) {
    peer();
    var options = properties.getOutbound();
    var request = HttpRequest.newBuilder(URI.create(options.getBaseUrl().replaceAll("/+$", "") + "/mock/oa/v1" + path))
        .timeout(Duration.ofMillis(options.getReadTimeoutMs())).header("X-Mock-OA-Key", options.getSecret())
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
      call.result("HTTP_RECEIVED", response.statusCode(), null);
      byte[] bytes = response.body();
      if (bytes.length > 2 * 1024 * 1024 || response.statusCode() >= 500 || response.statusCode() < 200
          || response.statusCode() >= 300 && response.statusCode() < 400) throw unknown("OA 通信结果未确认");
      var json = mapper.readTree(bytes);
      if (json == null || !json.isObject() || !json.path("mock").asBoolean(false)
          || !"MOCK".equals(json.path("mode").asText()) || !peer().environment().equals(json.path("environment").asText())) {
        throw unknown("OA 回执来源模式或环境不匹配");
      }
      return new Reply(response.statusCode(), json);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw unknown("OA 请求中断，请先核实原请求");
    } catch (IOException exception) {
      throw unknown("OA 连接失败或响应超时，请先核实原请求");
    }
  }

  private void requireSuccess(Reply reply, String code, String message) {
    if (reply.status() < 200 || reply.status() >= 300) throw OaIntegrationException.conflict(code, message);
  }

  private String command(Map<String, Object> payload) {
    return codec.write(Map.of("schemaVersion", 1, "environment", peer().environment(), "requestId", "java-" + UUID.randomUUID(), "payload", payload));
  }

  private OaDeliveryUnknownException unknown(String message) { return new OaDeliveryUnknownException(message); }
  private record Reply(int status, JsonNode body) {}
}
