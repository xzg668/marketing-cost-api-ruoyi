package com.sanhua.marketingcost.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.HashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 在Security过滤链之后绑定真实账号范围，每次请求结束清理，配置变更下一次请求即生效。 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE-10)
public class QuoteVisibilityFilter extends OncePerRequestFilter {
  private final QuoteVisibilityPolicy policy;
  public QuoteVisibilityFilter(QuoteVisibilityPolicy policy) { this.policy=policy; }
  @Override protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getServletPath();
    // 集成回调仍遵守各自的认证和单据绑定，公共来源查询不继承报价员范围。
    return java.util.List.of("/api/v1/quote-requests", "/api/v1/quote-ingest", "/api/v1/oa-forms",
        "/api/v1/cost-run", "/api/v1/quote-bom-details", "/api/v2/technical-data").stream()
        .noneMatch(root -> path.equals(root) || path.startsWith(root + "/"));
  }
  @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
      throws ServletException,IOException {
    var cache=new HashMap<Long,Boolean>();
    try {
      QuoteVisibilityContext.bind(policy.resolve(SecurityContextHolder.getContext().getAuthentication()),
          id -> cache.computeIfAbsent(id,policy::canReadForm));
      chain.doFilter(request,response);
    } finally { QuoteVisibilityContext.clear(); }
  }
}
