package com.sanhua.marketingcost.dto;

import java.time.LocalDate;

/**
 * 规则 CRUD 的请求体（新增 / 修改复用）。
 *
 * <p>字段对应 {@link com.sanhua.marketingcost.entity.BomStopDrillRule}。
 */
public class DrillRuleUpsertRequest {

  private String matchType;
  private String matchValue;
  /** T8：复合条件 JSON（非空则优先生效）。结构见 DrillRuleCondition。 */
  private String matchConditionJson;
  private String drillAction;
  private Integer markSubtreeCostRequired;
  private String replaceToCode;
  private Integer priority;
  private Integer enabled;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String businessUnitType;
  private String remark;

  public String getMatchType() {
    return matchType;
  }

  public void setMatchType(String matchType) {
    this.matchType = matchType;
  }

  public String getMatchValue() {
    return matchValue;
  }

  public void setMatchValue(String matchValue) {
    this.matchValue = matchValue;
  }

  public String getMatchConditionJson() {
    return matchConditionJson;
  }

  public void setMatchConditionJson(String matchConditionJson) {
    this.matchConditionJson = matchConditionJson;
  }

  public String getDrillAction() {
    return drillAction;
  }

  public void setDrillAction(String drillAction) {
    this.drillAction = drillAction;
  }

  public Integer getMarkSubtreeCostRequired() {
    return markSubtreeCostRequired;
  }

  public void setMarkSubtreeCostRequired(Integer markSubtreeCostRequired) {
    this.markSubtreeCostRequired = markSubtreeCostRequired;
  }

  public String getReplaceToCode() {
    return replaceToCode;
  }

  public void setReplaceToCode(String replaceToCode) {
    this.replaceToCode = replaceToCode;
  }

  public Integer getPriority() {
    return priority;
  }

  public void setPriority(Integer priority) {
    this.priority = priority;
  }

  public Integer getEnabled() {
    return enabled;
  }

  public void setEnabled(Integer enabled) {
    this.enabled = enabled;
  }

  public LocalDate getEffectiveFrom() {
    return effectiveFrom;
  }

  public void setEffectiveFrom(LocalDate effectiveFrom) {
    this.effectiveFrom = effectiveFrom;
  }

  public LocalDate getEffectiveTo() {
    return effectiveTo;
  }

  public void setEffectiveTo(LocalDate effectiveTo) {
    this.effectiveTo = effectiveTo;
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
