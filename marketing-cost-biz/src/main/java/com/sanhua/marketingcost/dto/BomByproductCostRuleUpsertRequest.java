package com.sanhua.marketingcost.dto;

import java.time.LocalDate;

/** 副产品附加结算规则新增 / 修改请求；独立于 BOM 树节点 settlement rule 页面。 */
public class BomByproductCostRuleUpsertRequest {

  private String ruleCode;
  private String ruleName;
  private String ruleCategory;
  private String addConditionType;
  private String settlementRowType;
  private String matchConditionJson;
  private Integer priority;
  private Integer enabled;
  private String businessUnitType;
  private String bomPurpose;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;

  public String getRuleCode() {
    return ruleCode;
  }

  public void setRuleCode(String ruleCode) {
    this.ruleCode = ruleCode;
  }

  public String getRuleName() {
    return ruleName;
  }

  public void setRuleName(String ruleName) {
    this.ruleName = ruleName;
  }

  public String getRuleCategory() {
    return ruleCategory;
  }

  public void setRuleCategory(String ruleCategory) {
    this.ruleCategory = ruleCategory;
  }

  public String getAddConditionType() {
    return addConditionType;
  }

  public void setAddConditionType(String addConditionType) {
    this.addConditionType = addConditionType;
  }

  public String getSettlementRowType() {
    return settlementRowType;
  }

  public void setSettlementRowType(String settlementRowType) {
    this.settlementRowType = settlementRowType;
  }

  public String getMatchConditionJson() {
    return matchConditionJson;
  }

  public void setMatchConditionJson(String matchConditionJson) {
    this.matchConditionJson = matchConditionJson;
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

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getBomPurpose() {
    return bomPurpose;
  }

  public void setBomPurpose(String bomPurpose) {
    this.bomPurpose = bomPurpose;
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

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
