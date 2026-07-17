package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import com.sanhua.marketingcost.enums.QuotePriceScenarioType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;
import org.springframework.util.StringUtils;

/** 联动价按需确保请求：业务入口先传本次要用的料号，ensure 负责缺失/过期才重算并落库。 */
@Getter
@Setter
public class LinkedPriceEnsureRequest {
  private LinkedPriceCalcScene calcScene;
  private String oaNo;
  private String businessUnitType;
  private String pricingMonth;
  private Long adjustBatchId;
  private Set<String> itemCodes = new LinkedHashSet<>();
  private boolean forceRefresh;
  private LocalDateTime priceAsOfTime;
  private QuotePriceScenarioType priceScenarioType = QuotePriceScenarioType.OA_LOCKED;
  private Map<String, BigDecimal> variableOverrides = Map.of();

  public LinkedPriceEnsureRequest() {
  }

  public LinkedPriceEnsureRequest(
      LinkedPriceCalcScene calcScene,
      String oaNo,
      String businessUnitType,
      String pricingMonth,
      Long adjustBatchId,
      Set<String> itemCodes,
      boolean forceRefresh) {
    this.calcScene = calcScene;
    this.oaNo = oaNo;
    this.businessUnitType = businessUnitType;
    this.pricingMonth = pricingMonth;
    this.adjustBatchId = adjustBatchId;
    setItemCodes(itemCodes);
    this.forceRefresh = forceRefresh;
  }

  public static LinkedPriceEnsureRequest quote(
      String oaNo, String businessUnitType, String pricingMonth, Set<String> itemCodes) {
    return new LinkedPriceEnsureRequest(
        LinkedPriceCalcScene.QUOTE, oaNo, businessUnitType, pricingMonth, null, itemCodes, false);
  }

  public static LinkedPriceEnsureRequest monthlyAdjust(
      Long adjustBatchId, String businessUnitType, String pricingMonth, Set<String> itemCodes) {
    return monthlyAdjust(adjustBatchId, businessUnitType, pricingMonth, itemCodes, false, null);
  }

  public static LinkedPriceEnsureRequest monthlyAdjust(
      Long adjustBatchId,
      String businessUnitType,
      String pricingMonth,
      Set<String> itemCodes,
      boolean forceRefresh) {
    return monthlyAdjust(adjustBatchId, businessUnitType, pricingMonth, itemCodes, forceRefresh, null);
  }

  public static LinkedPriceEnsureRequest monthlyAdjust(
      Long adjustBatchId,
      String businessUnitType,
      String pricingMonth,
      Set<String> itemCodes,
      boolean forceRefresh,
      LocalDateTime priceAsOfTime) {
    LinkedPriceEnsureRequest request = new LinkedPriceEnsureRequest(
        LinkedPriceCalcScene.MONTHLY_ADJUST,
        null,
        businessUnitType,
        pricingMonth,
        adjustBatchId,
        itemCodes,
        forceRefresh);
    request.setPriceAsOfTime(priceAsOfTime);
    return request;
  }

  public void setItemCodes(Set<String> itemCodes) {
    this.itemCodes = normalizeItemCodes(itemCodes);
  }

  public Set<String> normalizedItemCodes() {
    return normalizeItemCodes(itemCodes);
  }

  public List<String> validate() {
    List<String> errors = new ArrayList<>();
    if (calcScene == null) {
      errors.add("calcScene 不能为空");
    }
    if (!StringUtils.hasText(businessUnitType)) {
      errors.add("businessUnitType 不能为空");
    }
    if (!StringUtils.hasText(pricingMonth)) {
      errors.add("pricingMonth 不能为空");
    }
    if (normalizedItemCodes().isEmpty()) {
      errors.add("itemCodes 不能为空");
    }
    if (calcScene != null && calcScene.requiresOaNo() && !StringUtils.hasText(oaNo)) {
      errors.add("QUOTE 场景 oaNo 不能为空");
    }
    if (priceScenarioType == QuotePriceScenarioType.FINANCE_QUOTE_BASE) {
      Map<String, BigDecimal> overrides = normalizedVariableOverrides();
      if (overrides.size() != 1 || !overrides.containsKey("Cu")) {
        errors.add("FINANCE_QUOTE_BASE 场景只允许覆盖 Cu");
      } else if (overrides.get("Cu") == null || overrides.get("Cu").compareTo(BigDecimal.ZERO) <= 0) {
        errors.add("FINANCE_QUOTE_BASE 场景 Cu 必须大于0");
      }
    }
    return errors;
  }

  public void setPriceScenarioType(QuotePriceScenarioType priceScenarioType) {
    this.priceScenarioType = priceScenarioType == null
        ? QuotePriceScenarioType.OA_LOCKED
        : priceScenarioType;
  }

  public void setVariableOverrides(Map<String, BigDecimal> variableOverrides) {
    this.variableOverrides = normalizedOverrides(variableOverrides);
  }

  public Map<String, BigDecimal> normalizedVariableOverrides() {
    return normalizedOverrides(variableOverrides);
  }

  private Set<String> normalizeItemCodes(Set<String> source) {
    Set<String> normalized = new LinkedHashSet<>();
    if (source == null || source.isEmpty()) {
      return normalized;
    }
    for (String itemCode : source) {
      if (StringUtils.hasText(itemCode)) {
        normalized.add(itemCode.trim());
      }
    }
    return normalized;
  }

  private Map<String, BigDecimal> normalizedOverrides(Map<String, BigDecimal> source) {
    if (source == null || source.isEmpty()) {
      return Map.of();
    }
    Map<String, BigDecimal> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> entry : source.entrySet()) {
      if (StringUtils.hasText(entry.getKey())) {
        normalized.put(entry.getKey().trim(), entry.getValue());
      }
    }
    return Map.copyOf(normalized);
  }
}
