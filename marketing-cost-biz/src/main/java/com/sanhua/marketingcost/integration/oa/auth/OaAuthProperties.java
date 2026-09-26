package com.sanhua.marketingcost.integration.oa.auth;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 一套 OA 应用凭证对应一个鉴权组件；测试与正式环境通过各自的部署配置隔离。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "integration.oa-auth")
public class OaAuthProperties {
  private String baseUrl;
  private String callSysCode;
  private String corpId;
  private String appKey;
  private String appSecret;
  private int connectTimeoutMs = 5000;
  private int readTimeoutMs = 30000;
  private int maxAttempts = 5;

  @PostConstruct
  void validate() {
    if (connectTimeoutMs < 100 || connectTimeoutMs > 30000
        || readTimeoutMs < 1000 || readTimeoutMs > 120000
        || maxAttempts < 1 || maxAttempts > 10) {
      throw new IllegalStateException("OA 鉴权超时或重试配置无效");
    }
    if (StringUtils.hasText(baseUrl) || StringUtils.hasText(callSysCode)
        || StringUtils.hasText(corpId) || StringUtils.hasText(appKey)
        || StringUtils.hasText(appSecret)) {
      requireConfigured();
    }
  }

  public void requireConfigured() {
    if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(callSysCode)
        || !StringUtils.hasText(corpId) || !StringUtils.hasText(appKey)
        || !StringUtils.hasText(appSecret)) {
      throw new IllegalStateException("调用 OA 需要完整配置 integration.oa-auth 地址、调用方编码和应用凭证");
    }
    try {
      URI uri = URI.create(baseUrl);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new IllegalStateException("OA 鉴权地址必须是明确的 HTTP(S) 基地址");
    }
  }
}
