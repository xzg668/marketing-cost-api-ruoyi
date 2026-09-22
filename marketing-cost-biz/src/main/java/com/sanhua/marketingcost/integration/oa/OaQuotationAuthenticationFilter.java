package com.sanhua.marketingcost.integration.oa;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** I01/I04/I08 只使用文档中的 Bearer Token；调用方、环境和权限从服务端配置获得。 */
final class OaQuotationAuthenticationFilter extends OncePerRequestFilter {
  private final OaIntegrationProperties properties;
  private final ObjectMapper json;

  OaQuotationAuthenticationFilter(OaIntegrationProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (properties.getMode() == OaIntegrationProperties.Mode.DISABLED) {
      reject(response, 503, "INTEGRATION_DISABLED", "OA接口未启用");
      return;
    }
    String header = request.getHeader("Authorization");
    String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    OaIntegrationProperties.Client matched = null;
    if (token != null && !token.isBlank()) {
      for (var client : properties.getClients().values()) {
        if (client.getSecret() != null
            && MessageDigest.isEqual(
                client.getSecret().getBytes(StandardCharsets.UTF_8),
                token.getBytes(StandardCharsets.UTF_8))) {
          if (matched != null) {
            reject(response, 401, "INVALID_TOKEN", "调用方凭据配置不唯一");
            return;
          }
          matched = client;
        }
      }
    }
    if (matched == null
        || matched.getMode() != properties.getMode()
        || !properties.getEnvironment().equals(matched.getEnvironment())) {
      reject(response, 401, "INVALID_TOKEN", "Bearer Token无效");
      return;
    }
    var peer =
        new OaPeer(matched.getSourceSystem(), matched.getEnvironment(), matched.getBusinessUnits());
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(new UsernamePasswordAuthenticationToken(peer, null, List.of()));
    SecurityContextHolder.setContext(context);
    try {
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private void reject(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json;charset=UTF-8");
    json.writeValue(response.getOutputStream(), OaQuotationResponse.rejected(code, message, null));
  }
}
