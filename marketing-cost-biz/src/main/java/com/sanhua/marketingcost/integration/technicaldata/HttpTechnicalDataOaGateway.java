package com.sanhua.marketingcost.integration.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.oa.OaIntegrationException;
import com.sanhua.marketingcost.integration.oa.OaIntegrationProperties;
import com.sanhua.marketingcost.integration.oa.OaMessageCodec;
import com.sanhua.marketingcost.integration.oa.OaPeer;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    if (!enabled()) throw OaIntegrationException.conflict("OA_OUTBOUND_DISABLED", "OA 分派和审批通道未启用，草稿仍可保存");
    var client = properties.getClients().get(properties.getOutbound().getClientId());
    return new OaPeer(client.getSourceSystem(), client.getEnvironment(), client.getBusinessUnits());
  }

  @Override public Receipt send(Operation operation, String commandJson) {
    String path = switch (operation) {
      case TASK_DISPATCH -> "/tasks";
      case TECH_SUBMISSION -> "/submissions";
      case TECH_RETURN -> "/returns";
      case QUOTE_STATUS -> "/quotation/status";
      case QUOTE_DATA_SUBMIT -> "/quotation/data-submit";
      case QUOTE_RESULT_SAVE -> "/quotation/result-save";
      case QUOTE_COST_SUBMIT -> "/quotation/cost-submit";
    };
    var command = parse(commandJson);
    return receipt(command.path("requestId").asText(), request("POST", path, commandJson));
  }

  @Override public Optional<Receipt> query(Operation operation, String requestId) {
    requireRequestId(requestId);
    String kind = switch (operation) {
      case TASK_DISPATCH -> "TASK";
      case TECH_SUBMISSION -> "SUBMISSION";
      case TECH_RETURN -> "RETURN";
      case QUOTE_STATUS, QUOTE_DATA_SUBMIT, QUOTE_RESULT_SAVE, QUOTE_COST_SUBMIT -> operation.name();
    };
    var reply = request("GET", "/requests/" + kind + "/" + requestId, null);
    if (reply.status() == 404 && "REQUEST_NOT_FOUND".equals(reply.body().path("code").asText())) return Optional.empty();
    if (reply.status() != 200 || !reply.body().path("found").asBoolean(false)
        || !reply.body().path("httpStatus").canConvertToInt()) throw unknown("OA 原请求状态暂时无法核实");
    return Optional.of(receipt(requestId, new Reply(reply.body().path("httpStatus").intValue(), reply.body().path("result"))));
  }

  @Override public Identity exchange(long taskId, String code) {
    var reply = request("POST", "/identity/exchange", command(Map.of("taskId", taskId, "code", code, "audience", "quote-workbench")));
    requireSuccess(reply, "OA_IDENTITY_REJECTED", "OA 身份码无效、过期或已使用");
    var identity = reply.body();
    if (!identity.path("taskId").canConvertToLong() || identity.path("taskId").longValue() != taskId
        || !identity.path("externalUserId").isTextual() || identity.path("externalUserId").asText().isBlank()) {
      throw unknown("OA 身份回执缺少匹配的任务和人员");
    }
    return new Identity(identity.path("externalUserId").asText(), taskId);
  }

  @Override public List<ExternalUser> users() {
    var reply = request("GET", "/users", null);
    requireSuccess(reply, "OA_DIRECTORY_UNAVAILABLE", "OA 人员目录暂不可用");
    if (!reply.body().path("users").isArray()) throw unknown("OA 人员目录回执格式不完整");
    List<ExternalUser> users = new ArrayList<>();
    for (JsonNode user : reply.body().path("users")) {
      if (!user.path("externalUserId").isTextual() || !user.path("displayName").isTextual()
          || !(user.path("active").isBoolean() || user.path("active").isIntegralNumber())) throw unknown("OA 人员目录字段不完整");
      users.add(new ExternalUser(user.path("externalUserId").asText(), user.path("displayName").asText(),
          user.path("active").isBoolean() ? user.path("active").booleanValue() : user.path("active").intValue() == 1));
    }
    return List.copyOf(users);
  }

  @Override public String taskAccessUrl(long taskId) {
    peer();
    return properties.getOutbound().getFrontendBaseUrl().replaceAll("/+$", "") + "/technical-data-access?taskId=" + taskId;
  }

  private Receipt receipt(String requestId, Reply reply) {
    if (!requestId.equals(reply.body().path("requestId").asText())) throw unknown("OA 回执与原请求编号不一致");
    if (reply.status() >= 200 && reply.status() < 300 && reply.body().path("accepted").isBoolean()
        && reply.body().path("accepted").booleanValue()) return new Receipt(requestId, true, null, reply.body());
    if (reply.status() >= 400 && reply.status() < 500 && reply.body().path("accepted").isBoolean()
        && !reply.body().path("accepted").booleanValue()) {
      return new Receipt(requestId, false, reply.body().path("code").asText("OA_REJECTED"), reply.body());
    }
    throw unknown("OA 未返回明确的受理或拒绝结果");
  }

  private Reply request(String method, String path, String body) {
    peer();
    var options = properties.getOutbound();
    var request = HttpRequest.newBuilder(URI.create(options.getBaseUrl().replaceAll("/+$", "") + "/mock/oa/v1" + path))
        .timeout(Duration.ofMillis(options.getReadTimeoutMs())).header("X-Mock-OA-Key", options.getSecret())
        .header("Content-Type", "application/json")
        .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build();
    try {
      var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
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

  private JsonNode parse(String value) {
    try { return mapper.readTree(value); }
    catch (IOException exception) { throw new IllegalArgumentException("OA 待发送契约格式错误"); }
  }

  private void requireRequestId(String value) {
    if (value == null || !value.matches("[A-Za-z0-9._:-]{1,128}")) throw new IllegalArgumentException("请求编号不合法");
  }

  private OaDeliveryUnknownException unknown(String message) { return new OaDeliveryUnknownException(message); }
  private record Reply(int status, JsonNode body) {}
}
