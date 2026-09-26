package com.sanhua.marketingcost.integration.oa;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "integration.oa")
public class OaIntegrationProperties {
  public enum Mode { DISABLED, MOCK, REAL }

  private Mode mode = Mode.DISABLED;
  private String environment;
  private int maxBodyBytes = 2 * 1024 * 1024;
  private Map<String, Client> clients = new LinkedHashMap<>();
  private Outbound outbound = new Outbound();

  @PostConstruct
  void validate() {
    if (maxBodyBytes < 1024 || maxBodyBytes > 10 * 1024 * 1024) {
      throw new IllegalStateException("OA request body size configuration is invalid");
    }
    if (mode == Mode.DISABLED) return;
    if (environment == null || !environment.matches("[A-Za-z0-9._-]{1,32}") || clients.isEmpty()) {
      throw new IllegalStateException("Enabled OA integration requires an environment and configured clients");
    }
    clients.forEach((id, client) -> {
      if (!id.matches("[A-Za-z0-9._-]{1,64}") || client == null || client.mode != mode
          || !environment.equals(client.environment) || client.sourceSystem == null
          || !client.sourceSystem.matches("[A-Za-z0-9._-]{1,64}") || client.secret == null || client.secret.length() < 32
          || client.businessUnits == null || client.businessUnits.isEmpty()
          || !Set.of("COMMERCIAL", "HOUSEHOLD").containsAll(client.businessUnits)) {
        throw new IllegalStateException("OA client configuration does not match the active environment, mode or permissions");
      }
    });
    if (outbound.enabled) {
      if (mode != Mode.MOCK) {
        throw new IllegalStateException("OA outbound REAL adapter is not configured; mock cannot serve REAL mode");
      }
      if (!clients.containsKey(outbound.clientId) || outbound.secret == null || outbound.secret.length() < 32
          || outbound.connectTimeoutMs < 100 || outbound.connectTimeoutMs > 30000
          || outbound.readTimeoutMs < 100 || outbound.readTimeoutMs > 60000) {
        throw new IllegalStateException("OA outbound requires a configured peer, private key and bounded timeouts");
      }
      validateHttpUrl(outbound.baseUrl);
      validateHttpUrl(outbound.frontendBaseUrl);
    }
  }

  private static void validateHttpUrl(String value) {
    try {
      var uri = java.net.URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new IllegalStateException("OA outbound and frontend URLs must be explicit HTTP addresses");
    }
  }

  @Getter
  @Setter
  public static class Outbound {
    private boolean enabled;
    private String clientId;
    private String baseUrl;
    private String secret;
    private String frontendBaseUrl;
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
  }

  @Getter
  @Setter
  public static class Client {
    private String sourceSystem;
    private String environment;
    private String secret;
    private Mode mode = Mode.DISABLED;
    private Set<String> businessUnits = Set.of();
  }
}
