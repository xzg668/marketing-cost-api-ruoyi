package com.sanhua.marketingcost.integration.oa.oauth;

import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** E10 人员授权配置；与后台公共 OA token 的应用凭证分开。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "integration.oa-oauth")
public class OaOAuthProperties {
  private String baseUrl;
  private String clientId;
  private String clientSecret;
  private String redirectUri;
  private int connectTimeoutMs = 5000;
  private int readTimeoutMs = 15000;
  private int sessionSeconds = 7200;

  public void requireConfigured() {
    if (!StringUtils.hasText(clientId) || !StringUtils.hasText(clientSecret)) {
      throw new OaOAuthException("NOT_CONFIGURED", "OA 免登尚未配置，请联系管理员");
    }
    validateUrl(baseUrl);
    validateUrl(redirectUri);
    if (connectTimeoutMs < 100 || connectTimeoutMs > 30000 || readTimeoutMs < 100
        || readTimeoutMs > 60000 || sessionSeconds < 60 || sessionSeconds > 7200) {
      throw new OaOAuthException("NOT_CONFIGURED", "OA 免登超时配置无效");
    }
  }

  public static void validateUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new OaOAuthException("NOT_CONFIGURED", "OA 免登地址配置无效");
    }
  }
}
