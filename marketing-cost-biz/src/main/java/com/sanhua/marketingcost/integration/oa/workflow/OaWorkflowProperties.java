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
  /** I07 明细表 ID；家用按新品/衍生品与批量品分别配置，字符串避免大整数精度丢失。 */
  private Map<String, String> finalCostSubFormIds = new LinkedHashMap<>();
  /** I02/I05 共用的技术员表单字段；家用 FI-SR-005 使用已确认的同名字段。 */
  private Map<String, String> technicalPeopleDataKeys = new LinkedHashMap<>(Map.of(
      "FI-SC-005", "jsy",
      "FI-SR-005", "FI-SR-005"));

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

  public long requireFinalCostSubFormId(String processCode, String businessType) {
    String tableKey = processCode;
    if ("FI-SR-005".equals(processCode)) {
      if (businessType == null) {
        throw new IllegalArgumentException("FI-SR-005 缺少原报价需求单的业务类型");
      }
      tableKey = switch (businessType) {
        case "新品", "衍生品" -> "FI-SR-005_NEW";
        case "批量品" -> "FI-SR-005_MASS";
        default -> throw new IllegalArgumentException("FI-SR-005 业务类型须为新品、衍生品或批量品");
      };
    }
    String value = finalCostSubFormIds == null ? null : finalCostSubFormIds.get(tableKey);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("未配置 " + tableKey + " 的 I07 明细表 ID（subFormId）");
    }
    try {
      if (!value.matches("[0-9]+")) throw new NumberFormatException();
      long id = Long.parseLong(value);
      if (id <= 0) throw new NumberFormatException();
      return id;
    } catch (NumberFormatException invalid) {
      throw new IllegalArgumentException(tableKey + " 的 I07 明细表 ID（subFormId）须为有效的正整数 LONG");
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
