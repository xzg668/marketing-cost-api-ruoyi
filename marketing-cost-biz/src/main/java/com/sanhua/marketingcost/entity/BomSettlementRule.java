package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * BOM 树节点结算规则。
 *
 * <p>本表只表达 BOM 树节点上的结算粒度判断，例如特殊采购分类上卷父件、辅料排除、
 * 包装截断等。副产品附加结算行不属于树节点规则，单独放在 {@link BomByproductCostRule}。
 */
@TableName("lp_bom_settlement_rule")
public class BomSettlementRule {

  @TableId(type = IdType.AUTO)
  private Long id;

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

  @TableField(fill = FieldFill.INSERT)
  private String businessUnitType;

  private String bomPurpose;
  private LocalDate effectiveFrom;
  private LocalDate effectiveTo;
  private String remark;
  private String createdBy;

  @TableField(fill = FieldFill.INSERT)
  private LocalDateTime createdAt;

  private String updatedBy;

  @TableField(fill = FieldFill.INSERT_UPDATE)
  private LocalDateTime updatedAt;

  @TableLogic
  private Integer deleted;

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

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

  public String getCreatedBy() {
    return createdBy;
  }

  public void setCreatedBy(String createdBy) {
    this.createdBy = createdBy;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public String getUpdatedBy() {
    return updatedBy;
  }

  public void setUpdatedBy(String updatedBy) {
    this.updatedBy = updatedBy;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }

  public Integer getDeleted() {
    return deleted;
  }

  public void setDeleted(Integer deleted) {
    this.deleted = deleted;
  }
}
