package com.sanhua.marketingcost.integration.oa;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在鉴权之前记录接口边界，覆盖未登录、凭证无效、正文无效等未到业务方法的请求。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OaHttpLoggingFilter extends OncePerRequestFilter {
  static final String CALL = OaHttpLoggingFilter.class.getName() + ".call";
  static final String RESULT_CODE = OaHttpLoggingFilter.class.getName() + ".code";

  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    return !covers(request.getRequestURI().substring(request.getContextPath().length()));
  }

  static boolean covers(String path) {
    return path.equals("/open-api/v1/oa/quotation-requests") || path.startsWith("/open-api/v1/oa/quotation-requests/")
        || path.equals("/integration/v1/workflow-events") || path.startsWith("/integration/v1/workflow-events/")
        || path.startsWith("/api/v1/integration/oa/")
        || path.equals("/api/v1/oa-forms") || path.startsWith("/api/v1/oa-forms/")
        || path.startsWith("/api/v2/technical-data/access-tickets/")
        || path.equals("/api/v2/technical-data/returns") || path.startsWith("/api/v2/technical-data/returns/")
        || path.startsWith("/api/v2/technical-data/forms/")
        || path.equals("/api/v2/technical-data/assignees") || path.startsWith("/api/v2/technical-data/tasks/")
        || path.matches("/api/v1/quote-requests/[^/]+/(?:final-submission|material-confirmation)(?:/.*)?");
  }

  @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    try (var call = OaInterfaceLog.start("HTTP_INBOUND")) {
      call.field("direction", "INBOUND").field("method", request.getMethod()).field("endpoint", request.getRequestURI());
      request.setAttribute(CALL, call);
      response.setHeader("X-OA-Call-Id", call.id());
      try {
        chain.doFilter(request, response);
        String code = (String) request.getAttribute(RESULT_CODE);
        boolean rejected = response.getStatus() >= 400 || code != null && !"0".equals(code);
        call.result(response.getStatus() >= 500 ? "FAILED" : rejected ? "REJECTED" : "HTTP_COMPLETED", response.getStatus(), code);
      } catch (IOException | ServletException | RuntimeException exception) {
        call.failure(exception);
        throw exception;
      } finally {
        request.removeAttribute(CALL);
        request.removeAttribute(RESULT_CODE);
      }
    }
  }
}
