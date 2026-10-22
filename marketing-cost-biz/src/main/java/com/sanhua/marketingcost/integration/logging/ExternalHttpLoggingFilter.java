package com.sanhua.marketingcost.integration.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

/** 外部接入边界在鉴权之前建立日志，正文只随原处理链读取，不额外消费请求流。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ExternalHttpLoggingFilter extends OncePerRequestFilter {
  private static final String EXCHANGE_ATTRIBUTE = ExternalHttpLoggingFilter.class.getName() + ".exchange";

  /** Controller 只补充业务结果和关联号；请求/回执正文仍由本 Filter 记录一次。 */
  public static Optional<ExternalInterfaceLog.Exchange> exchange(HttpServletRequest request) {
    Object value = request.getAttribute(EXCHANGE_ATTRIBUTE);
    return value instanceof ExternalInterfaceLog.Exchange exchange ? Optional.of(exchange) : Optional.empty();
  }

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !(path.startsWith("/open-api/") || path.startsWith("/integration/")
        || path.startsWith("/api/v1/auth/oa/") || path.equals("/api/v1/quote-ingest/requests"));
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    var capturedRequest = new ContentCachingRequestWrapper(request, ExternalInterfaceLog.MAX_BODY_BYTES);
    var capturedResponse = new ContentCachingResponseWrapper(response);
    Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
    Collections.list(request.getHeaderNames()).forEach(name -> requestHeaders.put(name, Collections.list(request.getHeaders(name))));
    String address = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
    try (var log = ExternalInterfaceLog.start("EXTERNAL", "INBOUND", request.getMethod(), URI.create(address), requestHeaders)) {
      capturedRequest.setAttribute(EXCHANGE_ATTRIBUTE, log);
      response.setHeader("X-Interface-Call-Id", log.id());
      try {
        chain.doFilter(capturedRequest, capturedResponse);
      } catch (IOException | ServletException | RuntimeException failure) {
        log.failure(failure);
        throw failure;
      } finally {
        byte[] body = capturedRequest.getContentAsByteArray();
        log.request(new String(body, StandardCharsets.UTF_8));
        if (body.length == 0 && request.getContentLengthLong() != 0) log.requestUnavailable("NOT_READ_BY_HANDLER");
        else if (body.length >= ExternalInterfaceLog.MAX_BODY_BYTES) log.requestUnavailable("AT_CAPTURE_LIMIT");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        capturedResponse.getHeaderNames().forEach(name -> headers.put(name, List.copyOf(capturedResponse.getHeaders(name))));
        log.response(capturedResponse.getStatus(), headers, new String(capturedResponse.getContentAsByteArray(), StandardCharsets.UTF_8));
        capturedResponse.copyBodyToResponse();
      }
    }
  }
}
