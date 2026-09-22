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
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

final class OaMachineAuthenticationFilter extends OncePerRequestFilter {
  private final OaIntegrationProperties properties;
  private final ObjectMapper json;

  OaMachineAuthenticationFilter(OaIntegrationProperties properties, ObjectMapper json) {
    this.properties = properties;
    this.json = json;
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    if (properties.getMode() == OaIntegrationProperties.Mode.DISABLED) {
      reject(response, 503, "INTEGRATION_DISABLED", "OA 接口尚未启用");
      return;
    }
    String clientId = request.getHeader("X-OA-Client");
    String supplied = request.getHeader("X-OA-Key");
    var client = clientId == null ? null : properties.getClients().get(clientId);
    if (client == null || client.getSecret() == null || client.getSecret().length() < 32
        || supplied == null || !MessageDigest.isEqual(client.getSecret().getBytes(StandardCharsets.UTF_8),
            supplied.getBytes(StandardCharsets.UTF_8))
        || client.getMode() != properties.getMode()
        || client.getEnvironment() == null || !client.getEnvironment().equals(properties.getEnvironment())
        || client.getSourceSystem() == null || client.getSourceSystem().isBlank()
        || client.getBusinessUnits().isEmpty()) {
      reject(response, 401, "UNAUTHORIZED_PEER", "调用方凭据或环境不匹配");
      return;
    }
    var peer = new OaPeer(client.getSourceSystem(), client.getEnvironment(),
        java.util.Set.copyOf(client.getBusinessUnits()));
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
    json.writeValue(response.getOutputStream(), Map.of("received", false, "code", code, "message", message));
  }
}
