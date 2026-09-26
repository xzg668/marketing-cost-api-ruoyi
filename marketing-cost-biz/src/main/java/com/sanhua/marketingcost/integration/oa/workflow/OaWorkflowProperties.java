package com.sanhua.marketingcost.integration.oa.workflow;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "integration.oa-workflow")
public class OaWorkflowProperties {
  private boolean debugEnabled;
  /** 留空时沿用公共 OA 鉴权的基地址。 */
  private String baseUrl;
  private int readTimeoutMs = 30000;

  @PostConstruct
  void validate() {
    if (readTimeoutMs < 1000 || readTimeoutMs > 120000) {
      throw new IllegalStateException("OA 流程接口超时须在1000至120000毫秒之间");
    }
    if (baseUrl != null && !baseUrl.isBlank()) requireBaseUrl(baseUrl);
  }

  public String resolveBaseUrl(String authBaseUrl) {
    String value = baseUrl == null || baseUrl.isBlank() ? authBaseUrl : baseUrl;
    requireBaseUrl(value);
    return value.replaceAll("/+$", "");
  }

  private static void requireBaseUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new IllegalStateException("OA 流程接口需要明确的 HTTP(S) 基地址");
    }
  }
}
