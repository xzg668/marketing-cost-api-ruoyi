package com.sanhua.marketingcost.config;

import com.sanhua.marketingcost.enums.CostRunExecutionMode;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 普通报价核算执行模式开关。默认 TASK_WORKER，API_SYNC 作为回退模式。 */
@Component
@ConfigurationProperties(prefix = "cost.run.execution")
public class CostRunExecutionProperties {

  private String mode = CostRunExecutionMode.TASK_WORKER.getCode();
  private Set<String> grayBusinessUnits = new LinkedHashSet<>();
  private Set<String> grayUsers = new LinkedHashSet<>();

  public String getMode() {
    return mode;
  }

  public CostRunExecutionMode getModeType() {
    return CostRunExecutionMode.fromCode(mode);
  }

  public void setMode(String mode) {
    this.mode = mode;
  }

  public Set<String> getGrayBusinessUnits() {
    return Collections.unmodifiableSet(grayBusinessUnits);
  }

  public void setGrayBusinessUnits(Set<String> grayBusinessUnits) {
    this.grayBusinessUnits = normalizeSet(grayBusinessUnits, true);
  }

  public Set<String> getGrayUsers() {
    return Collections.unmodifiableSet(grayUsers);
  }

  public void setGrayUsers(Set<String> grayUsers) {
    this.grayUsers = normalizeSet(grayUsers, false);
  }

  public boolean hasGrayFilters() {
    return !grayBusinessUnits.isEmpty() || !grayUsers.isEmpty();
  }

  public boolean hasGrayBusinessUnits() {
    return !grayBusinessUnits.isEmpty();
  }

  public CostRunExecutionMode resolveMode(String username, String businessUnitType) {
    CostRunExecutionMode configuredMode = getModeType();
    if (configuredMode == CostRunExecutionMode.API_SYNC || !hasGrayFilters()) {
      return configuredMode;
    }
    if (matchesGrayUsers(username) || matchesGrayBusinessUnits(businessUnitType)) {
      return configuredMode;
    }
    return CostRunExecutionMode.API_SYNC;
  }

  private boolean matchesGrayUsers(String username) {
    if (!StringUtils.hasText(username)) {
      return false;
    }
    return grayUsers.contains(normalize(username, false));
  }

  private boolean matchesGrayBusinessUnits(String businessUnitType) {
    if (!StringUtils.hasText(businessUnitType)) {
      return false;
    }
    return grayBusinessUnits.contains(normalize(businessUnitType, true));
  }

  private Set<String> normalizeSet(Set<String> values, boolean uppercase) {
    if (values == null || values.isEmpty()) {
      return new LinkedHashSet<>();
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (String value : values) {
      String item = normalize(value, uppercase);
      if (StringUtils.hasText(item)) {
        normalized.add(item);
      }
    }
    return normalized;
  }

  private String normalize(String value, boolean uppercase) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String normalized = value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
    return uppercase ? normalized.toUpperCase(Locale.ROOT) : normalized.toLowerCase(Locale.ROOT);
  }
}
