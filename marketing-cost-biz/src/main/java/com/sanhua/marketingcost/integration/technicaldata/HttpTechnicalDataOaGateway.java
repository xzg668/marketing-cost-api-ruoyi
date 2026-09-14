package com.sanhua.marketingcost.integration.technicaldata;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class HttpTechnicalDataOaGateway implements TechnicalDataOaGateway {
  private final TechnicalDataOaProperties properties;
  private final ObjectMapper objectMapper;
  private final HttpClient client;

  @Autowired
  public HttpTechnicalDataOaGateway(
      TechnicalDataOaProperties properties, ObjectMapper objectMapper) {
    this(properties, objectMapper, HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  HttpTechnicalDataOaGateway(
      TechnicalDataOaProperties properties, ObjectMapper objectMapper, HttpClient client) {
    this.properties = properties;
    this.objectMapper = objectMapper;
    this.client = client;
  }

  @Override
  public PublishResult publish(PublishCommand command) {
    if (!properties.isEnabled()) throw new IllegalStateException("OA技术待办发布未启用");
    if (!StringUtils.hasText(properties.getBaseUrl())) {
      throw new IllegalStateException("OA技术待办发布地址未配置");
    }
    try {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("businessType", "QUOTE_TECHNICAL_DATA");
      payload.put("businessId", command.taskId());
      payload.put("businessNo", command.taskNo());
      payload.put("oaNo", command.oaNo());
      payload.put("accountingMonth", command.accountingMonth());
      payload.put("assigneeUserId", command.assigneeUserId());
      payload.put("assigneeName", command.assigneeName());
      payload.put("dueAt", command.dueAt());
      payload.put("accessUrl", command.accessUrl());
      payload.put("idempotencyKey", command.idempotencyKey());
      HttpRequest.Builder builder = HttpRequest.newBuilder()
          .uri(URI.create(join(properties.getBaseUrl(), properties.getPublishPath())))
          .timeout(Duration.ofMillis(properties.getReadTimeoutMs()))
          .header("Content-Type", "application/json")
          .header("Idempotency-Key", command.idempotencyKey())
          .POST(HttpRequest.BodyPublishers.ofString(
              objectMapper.writeValueAsString(payload), StandardCharsets.UTF_8));
      if (StringUtils.hasText(properties.getAuthorization())) {
        builder.header("Authorization", properties.getAuthorization().trim());
      }
      HttpResponse<String> response = client.send(
          builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("OA返回HTTP " + response.statusCode());
      }
      JsonNode body = objectMapper.readTree(response.body());
      String externalTaskId = body.path("externalTaskId").asText(null);
      String status = body.path("status").asText("PUBLISHED");
      if (!StringUtils.hasText(externalTaskId)) throw new IllegalStateException("OA未返回外部任务ID");
      return new PublishResult(externalTaskId.trim(), status.trim());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("OA技术待办发布被中断", exception);
    } catch (Exception exception) {
      if (exception instanceof IllegalStateException state) throw state;
      throw new IllegalStateException("OA技术待办发布失败：" + exception.getMessage(), exception);
    }
  }

  private String join(String baseUrl, String path) {
    return baseUrl.replaceAll("/+$", "") + "/" + path.replaceAll("^/+", "");
  }
}
