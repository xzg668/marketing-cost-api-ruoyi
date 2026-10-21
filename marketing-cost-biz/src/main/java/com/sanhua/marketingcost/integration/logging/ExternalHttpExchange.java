package com.sanhua.marketingcost.integration.logging;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/** 仅围绕既有 send 记录报文；不更改请求、重试策略或异常传播。 */
public final class ExternalHttpExchange {
  private ExternalHttpExchange() {}

  public static HttpResponse<String> sendJson(String system, HttpClient client, HttpRequest request,
      String requestBody, String... secrets) throws IOException, InterruptedException {
    return sendJson(system, client, request, requestBody, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8), secrets);
  }

  public static HttpResponse<String> sendJson(String system, HttpClient client, HttpRequest request,
      String requestBody, HttpResponse.BodyHandler<String> handler, String... secrets) throws IOException, InterruptedException {
    try (var log = ExternalInterfaceLog.start(system, "OUTBOUND", request.method(), request.uri(), request.headers().map())) {
      log.request(requestBody);
      log.redactSecrets(secrets);
      try {
        HttpResponse<String> response = client.send(request, handler);
        log.response(response.statusCode(), response.headers().map(), response.body());
        return response;
      } catch (IOException | InterruptedException | RuntimeException failure) {
        log.failure(failure);
        throw failure;
      }
    }
  }
}
