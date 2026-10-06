package com.sanhua.marketingcost.integration.oa.workflow;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
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
  /** 退回接口可由 OA 直接提供，与 ESB 提交入口分别配置；留空沿用流程基地址。 */
  private String rejectBaseUrl;
  private int readTimeoutMs = 30000;
  /** I07 的 OA 明细表 ID，按流程配置；与产品明细行 rowId 无关。字符串配置避免大整数精度丢失。 */
  private Map<String, String> finalCostSubFormIds = new LinkedHashMap<>();
  /** I02/I05 共用的技术员表单字段。只默认已确认的005；其他流程须显式配置。 */
  private Map<String, String> technicalPeopleDataKeys = new LinkedHashMap<>(Map.of("FI-SC-005", "jsy"));

  public String requireTechnicalPeopleDataKey(String processCode) {
    String value = technicalPeopleDataKeys == null ? null : technicalPeopleDataKeys.get(processCode);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("未配置 " + processCode + " 的技术员字段 dataKey，请先确认OA字段");
    }
    String key = value.trim();
    if (key.length() > 128 || key.codePoints().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(processCode + " 的技术员字段 dataKey 格式不正确");
    }
    return key;
  }

  public long requireFinalCostSubFormId(String processCode) {
    String value = finalCostSubFormIds == null ? null : finalCostSubFormIds.get(processCode);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("未配置 " + processCode + " 的 I07 明细表 ID（subFormId）");
    }
    try {
      if (!value.matches("[0-9]+")) throw new NumberFormatException();
      long id = Long.parseLong(value);
      if (id <= 0) throw new NumberFormatException();
      return id;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(processCode + " 的 I07 明细表 ID（subFormId）须为有效的正整数 LONG");
    }
  }

  @PostConstruct
  void validate() {
    if (readTimeoutMs < 1000 || readTimeoutMs > 120000) {
      throw new IllegalStateException("OA 流程接口超时须在1000至120000毫秒之间");
    }
    if (baseUrl != null && !baseUrl.isBlank()) requireBaseUrl(baseUrl);
    if (rejectBaseUrl != null && !rejectBaseUrl.isBlank()) requireBaseUrl(rejectBaseUrl);
  }

  public String resolveBaseUrl(String authBaseUrl) {
    String value = baseUrl == null || baseUrl.isBlank() ? authBaseUrl : baseUrl;
    requireBaseUrl(value);
    return value.replaceAll("/+$", "");
  }

  public String resolveRejectBaseUrl(String authBaseUrl) {
    if (rejectBaseUrl == null || rejectBaseUrl.isBlank()) return resolveBaseUrl(authBaseUrl);
    requireBaseUrl(rejectBaseUrl);
    return rejectBaseUrl.replaceAll("/+$", "");
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
