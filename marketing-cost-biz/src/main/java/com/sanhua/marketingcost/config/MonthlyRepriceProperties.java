package com.sanhua.marketingcost.config;

import com.sanhua.marketingcost.enums.MonthlyRepriceExecutionBackend;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 月度调价灰度发布开关。
 *
 * <p>{@code enabled=false} 时禁止新建月度调价批次，但查询、取消等回滚动作仍可用，避免
 * 灰度期间把未确认批次卡死。
 */
@Component
@ConfigurationProperties(prefix = "monthly.reprice")
public class MonthlyRepriceProperties {

  private boolean enabled = true;
  private String executionBackend = "LOCAL_WORKER";
  private List<String> allowedBusinessUnits = new ArrayList<>();

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getExecutionBackend() {
    String normalized = normalize(executionBackend);
    return StringUtils.hasText(normalized)
        ? normalized
        : MonthlyRepriceExecutionBackend.LOCAL_WORKER.getCode();
  }

  public MonthlyRepriceExecutionBackend getExecutionBackendType() {
    return MonthlyRepriceExecutionBackend.fromCode(getExecutionBackend());
  }

  public void setExecutionBackend(String executionBackend) {
    this.executionBackend = executionBackend;
  }

  public List<String> getAllowedBusinessUnits() {
    return allowedBusinessUnits;
  }

  public void setAllowedBusinessUnits(List<String> allowedBusinessUnits) {
    this.allowedBusinessUnits =
        allowedBusinessUnits == null ? new ArrayList<>() : allowedBusinessUnits;
  }

  public boolean isBusinessUnitAllowed(String businessUnitType) {
    List<String> normalizedAllowed = allowedBusinessUnits.stream()
        .map(this::normalize)
        .filter(StringUtils::hasText)
        .toList();
    if (normalizedAllowed.isEmpty()) {
      return true;
    }
    String normalizedBusinessUnit = normalize(businessUnitType);
    return normalizedAllowed.stream()
        .anyMatch(allowed -> allowed.equalsIgnoreCase(normalizedBusinessUnit));
  }

  private String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
