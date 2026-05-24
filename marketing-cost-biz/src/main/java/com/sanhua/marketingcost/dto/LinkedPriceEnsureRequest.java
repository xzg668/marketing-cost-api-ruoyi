package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.enums.LinkedPriceCalcScene;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
    return new LinkedPriceEnsureRequest(
        LinkedPriceCalcScene.MONTHLY_ADJUST,
        null,
        businessUnitType,
        pricingMonth,
        adjustBatchId,
        itemCodes,
        false);
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
    if (calcScene != null && calcScene.requiresAdjustBatchId() && adjustBatchId == null) {
      errors.add("MONTHLY_ADJUST 场景 adjustBatchId 不能为空");
    }
    return errors;
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
}
