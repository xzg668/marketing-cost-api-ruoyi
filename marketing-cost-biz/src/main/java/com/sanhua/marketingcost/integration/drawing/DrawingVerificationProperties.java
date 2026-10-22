package com.sanhua.marketingcost.integration.drawing;

import java.net.URI;
import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** PDF 核验服务配置独立于已退役的 nodes 和 Excel 协议。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "quote.drawing-verification")
public class DrawingVerificationProperties {
  private boolean enabled;
  private URI baseUrl = URI.create("http://10.10.206.115:8011");
  private String verifyPath = "/api/drawings/verify";
  private Duration connectTimeout = Duration.ofSeconds(5);
  private Duration requestTimeout = Duration.ofMinutes(5);

  public URI endpoint() {
    if (baseUrl == null || baseUrl.getHost() == null
        || !("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
        || baseUrl.getRawUserInfo() != null || baseUrl.getRawQuery() != null || baseUrl.getFragment() != null
        || verifyPath == null || !verifyPath.startsWith("/") || verifyPath.startsWith("//")
        || verifyPath.contains("?") || verifyPath.contains("#")) {
      throw new IllegalArgumentException("电子图库核验服务地址配置无效");
    }
    return URI.create(baseUrl.toString().replaceAll("/+$", "") + verifyPath);
  }
}
