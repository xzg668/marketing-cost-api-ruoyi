package com.sanhua.marketingcost.integration.oa.directory;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.List;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "integration.oa-person-directory")
public class OaPersonDirectoryProperties {
  private boolean enabled;
  private boolean runAtStartup;
  private String sourceSystem = "HZ_KG_OA_TST";
  private String environment = "TEST";
  private String authBaseUrl;
  private String queryBaseUrl;
  private String detailBaseUrl;
  private String callSysCode;
  private String corpId;
  private String appKey;
  private String appSecret;
  private String cron = "0 15 2 * * *";
  private String zone = "Asia/Shanghai";
  private int pageSize = 1000;
  private int connectTimeoutMs = 5000;
  private int readTimeoutMs = 30000;
  private int maxAttempts = 5;
  private int detailConcurrency = 4;
  private List<String> targetDepartments = List.of(
      "商用制冷业务单元/商用四通阀事业部",
      "商用制冷业务单元/商用部品事业部",
      "商用制冷业务单元/电子产品事业部",
      "商用制冷业务单元/板换事业部",
      "商用制冷业务单元/越南事业部",
      "商用制冷业务单元/技术中心");

  @PostConstruct
  void validate() {
    if (pageSize < 1 || pageSize > 1000 || connectTimeoutMs < 100 || connectTimeoutMs > 30000
        || readTimeoutMs < 1000 || readTimeoutMs > 120000 || maxAttempts < 1 || maxAttempts > 10
        || detailConcurrency < 1 || detailConcurrency > 16) {
      throw new IllegalStateException("OA 人员目录分页、超时或并发配置无效");
    }
    if (targetDepartments == null || targetDepartments.isEmpty()
        || targetDepartments.stream().anyMatch(value -> !StringUtils.hasText(value))
        || Set.copyOf(targetDepartments).size() != targetDepartments.size()) {
      throw new IllegalStateException("OA 人员目录目标部门不能为空或重复");
    }
    if (!enabled) return;
    if (!StringUtils.hasText(sourceSystem) || !StringUtils.hasText(environment)
        || !StringUtils.hasText(callSysCode) || !StringUtils.hasText(corpId)
        || !StringUtils.hasText(appKey) || !StringUtils.hasText(appSecret)) {
      throw new IllegalStateException("启用 OA 人员目录需要完整的环境和鉴权配置");
    }
    validateBaseUrl(authBaseUrl);
    validateBaseUrl(queryBaseUrl);
    validateBaseUrl(detailBaseUrl);
  }

  private void validateBaseUrl(String value) {
    try {
      URI uri = URI.create(value);
      if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
          || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw new IllegalStateException("OA 人员目录地址必须是明确的 HTTP(S) 基地址");
    }
  }
}
