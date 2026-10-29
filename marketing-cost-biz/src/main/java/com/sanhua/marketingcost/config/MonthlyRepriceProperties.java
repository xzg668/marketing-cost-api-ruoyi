package com.sanhua.marketingcost.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.time.YearMonth;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 月度调价灰度发布开关。
 *
 * <p>{@code enabled=false} 时禁止新建月度调价批次，但历史查询仍可用，避免
 * 灰度期间把未确认批次卡死。
 */
@Component
@ConfigurationProperties(prefix = "monthly.reprice")
public class MonthlyRepriceProperties {

  private boolean enabled = true;
  private List<String> allowedBusinessUnits = new ArrayList<>();
  // No inferred/default launch date: deployment must supply the verified month per business unit.
  private Map<String, String> goLiveMonths = new HashMap<>();

  public Map<String, String> getGoLiveMonths() { return goLiveMonths; }

  public void setGoLiveMonths(Map<String, String> values) {
    goLiveMonths = values == null ? new HashMap<>() : new HashMap<>(values);
  }

  public YearMonth requireGoLiveMonth(String unit) {
    com.sanhua.marketingcost.service.reprice.MonthlyRepriceContract.unit(unit);
    String value = goLiveMonths.get(unit);
    if (value == null || !value.matches("[0-9]{4}-[0-9]{2}"))
      throw new IllegalStateException("请配置已核实的实际上线月份：monthly.reprice.go-live-months." + unit);
    try { return YearMonth.parse(value); }
    catch (java.time.DateTimeException invalid) {
      throw new IllegalStateException("实际上线月份无效：" + unit, invalid);
    }
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
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
