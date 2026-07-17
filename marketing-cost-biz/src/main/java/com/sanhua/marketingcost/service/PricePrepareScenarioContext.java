package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.util.StringUtils;

/** 单次价格准备使用的场景上下文；FCQ-04 只固化上下文，变量覆盖执行由 FCQ-05 接入。 */
public record PricePrepareScenarioContext(
    QuotePriceScenarioType scenarioType,
    String scenarioGroupNo,
    String sourcePrepareNo,
    Map<String, BigDecimal> variableOverrides) {

  public PricePrepareScenarioContext {
    scenarioType = scenarioType == null ? QuotePriceScenarioType.OA_LOCKED : scenarioType;
    scenarioGroupNo = trimToNull(scenarioGroupNo);
    sourcePrepareNo = trimToNull(sourcePrepareNo);
    variableOverrides = immutableOverrides(variableOverrides);
    if (scenarioType == QuotePriceScenarioType.FINANCE_QUOTE_BASE) {
      if (scenarioGroupNo == null || sourcePrepareNo == null) {
        throw new IllegalArgumentException(
            "FINANCE_QUOTE_BASE场景必须提供scenarioGroupNo和sourcePrepareNo");
      }
      BigDecimal cu = variableOverrides.get("Cu");
      if (variableOverrides.size() != 1 || cu == null || cu.compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("FINANCE_QUOTE_BASE场景只允许覆盖大于0的Cu");
      }
    } else if (!variableOverrides.isEmpty()) {
      throw new IllegalArgumentException("OA_LOCKED场景不允许变量覆盖");
    }
  }

  private static Map<String, BigDecimal> immutableOverrides(
      Map<String, BigDecimal> overrides) {
    if (overrides == null || overrides.isEmpty()) {
      return Map.of();
    }
    Map<String, BigDecimal> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : overrides.entrySet()) {
      String variableCode = trimToNull(entry.getKey());
      if (variableCode == null) {
        throw new IllegalArgumentException("variableOverrides变量编码不能为空");
      }
      if (entry.getValue() == null) {
        throw new IllegalArgumentException("variableOverrides[" + variableCode + "]不能为空");
      }
      if (normalized.putIfAbsent(variableCode, entry.getValue()) != null) {
        throw new IllegalArgumentException("variableOverrides变量编码重复: " + variableCode);
      }
    }
    return Collections.unmodifiableMap(normalized);
  }

  private static String trimToNull(String value) {
    return StringUtils.hasText(value) ? value.trim() : null;
  }
}
