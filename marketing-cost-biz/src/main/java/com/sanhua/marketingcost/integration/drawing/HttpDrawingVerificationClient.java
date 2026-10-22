package com.sanhua.marketingcost.integration.drawing;

import static com.sanhua.marketingcost.integration.drawing.DrawingVerificationException.Code.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.integration.logging.ExternalInterfaceLog;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 一次显式 POST，无自动重试；总超时覆盖响应正文，长调用不包含数据库事务。 */
@Component
public class HttpDrawingVerificationClient implements DrawingVerificationClient {
  private final DrawingVerificationProperties properties;
  private final DrawingVerificationCodec codec;
  private final ObjectMapper json;
  private final HttpClient client;

  @Autowired
  public HttpDrawingVerificationClient(DrawingVerificationProperties properties,
      DrawingVerificationCodec codec, ObjectMapper json) {
    this(properties, codec, json, HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER).build());
  }

  HttpDrawingVerificationClient(DrawingVerificationProperties properties,
      DrawingVerificationCodec codec, ObjectMapper json, HttpClient client) {
    this.properties = properties;
    this.codec = codec;
    this.json = json;
    this.client = client;
  }

  @Override public DrawingVerificationPayload verify(Query query) {
    if (query == null || query.drawingNo() == null || query.drawingNo().isBlank()
        || query.requestId() == null || query.requestId().isBlank()) {
      throw new IllegalArgumentException("核验必须提供实际图号和本地请求号");
    }
    if (!properties.isEnabled()) {
      throw new DrawingVerificationException(NOT_CONFIGURED, "电子图库核验接口尚未启用", null, null, null);
    }
    String body = json.createObjectNode().put("drawingNo", query.drawingNo()).toString();
    HttpRequest request = HttpRequest.newBuilder(properties.endpoint()).timeout(properties.getRequestTimeout())
        .header("Content-Type", "application/json").header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
    try (var log = ExternalInterfaceLog.start("DRAWING_PDF", "OUTBOUND", "POST", request.uri(), request.headers().map())) {
      log.request(body);
      log.correlate("requestId", query.requestId());
      log.correlate("localTaskId", query.localTaskId());
      log.correlate("oaNo", query.oaNo());
      log.correlate("oaFormItemId", query.oaFormItemId());
      log.correlate("accountingMonth", query.accountingMonth());
      log.correlate("drawingNo", query.drawingNo());
      try {
        HttpResponse<String> response = exchange(request);
        log.response(response.statusCode(), response.headers().map(), response.body());
        if (response.statusCode() != 200) {
          throw new DrawingVerificationException(HTTP_ERROR, "电子图库核验失败，HTTP " + response.statusCode(),
              response.statusCode(), response.body(), null);
        }
        DrawingVerificationPayload payload;
        try {
          payload = codec.parse(response.body());
        } catch (IllegalArgumentException invalid) {
          throw new DrawingVerificationException(INVALID_RESPONSE, invalid.getMessage(),
              response.statusCode(), response.body(), invalid);
        }
        log.correlate("externalTaskId", payload.summary().taskId());
        log.result("SUMMARY_RECEIVED");
        return payload;
      } catch (DrawingVerificationException failure) {
        log.failure(failure);
        log.result(failure.getCode().name());
        if (failure.getHttpStatus() == null) log.responseUnavailable(failure.getCode().name());
        throw failure;
      } catch (RuntimeException failure) {
        log.failure(failure);
        log.result("TRANSPORT_ERROR");
        throw failure;
      }
    }
  }

  private HttpResponse<String> exchange(HttpRequest request) {
    CompletableFuture<HttpResponse<String>> pending = client.sendAsync(request,
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    try {
      return pending.get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      pending.cancel(true);
      throw new DrawingVerificationException(TIMEOUT, "电子图库核验超时，请核查任务后再决定是否重新发起", null, null, timeout);
    } catch (InterruptedException interrupted) {
      pending.cancel(true);
      Thread.currentThread().interrupt();
      throw new DrawingVerificationException(INTERRUPTED, "电子图库核验被中断", null, null, interrupted);
    } catch (ExecutionException failed) {
      Throwable cause = failed.getCause();
      if (cause instanceof HttpTimeoutException) {
        throw new DrawingVerificationException(TIMEOUT, "电子图库核验超时，请核查任务后再决定是否重新发起", null, null, cause);
      }
      throw new DrawingVerificationException(TRANSPORT_ERROR, "电子图库核验连接失败", null, null, cause);
    }
  }
}
