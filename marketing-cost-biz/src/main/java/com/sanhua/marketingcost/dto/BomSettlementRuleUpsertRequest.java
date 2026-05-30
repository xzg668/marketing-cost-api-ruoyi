package com.sanhua.marketingcost.dto;

import java.time.LocalDate;

/**
 * BOM 树节点结算规则新增 / 修改请求。
 *
 * <p>{@code settlementAction} 是真实结算动作：{@code ROLLUP_TO_PARENT} 表示命中采购件不直接结算、
 * 改由直接父件作为结算粒度；{@code EXCLUDE} 表示命中辅料末端不输出结算行。它不是页面展示口径。
 */
public class BomSettlementRuleUpsertRequest {

  private String ruleCode;
  private String ruleName;
  private String ruleCategory;
  private String settlementAction;
  private String settlementRowType;
  private String subRefType;
  private String matchConditionJson;
  private Integer markSubtreeCostRequired;
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

  public String getSettlementAction() {
    return settlementAction;
  }

  public void setSettlementAction(String settlementAction) {
    this.settlementAction = settlementAction;
  }

  public String getSettlementRowType() {
    return settlementRowType;
  }

  public void setSettlementRowType(String settlementRowType) {
    this.settlementRowType = settlementRowType;
  }

  public String getSubRefType() {
    return subRefType;
  }

  public void setSubRefType(String subRefType) {
    this.subRefType = subRefType;
  }

  public String getMatchConditionJson() {
    return matchConditionJson;
  }

  public void setMatchConditionJson(String matchConditionJson) {
    this.matchConditionJson = matchConditionJson;
  }

  public Integer getMarkSubtreeCostRequired() {
    return markSubtreeCostRequired;
  }

  public void setMarkSubtreeCostRequired(Integer markSubtreeCostRequired) {
    this.markSubtreeCostRequired = markSubtreeCostRequired;
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
