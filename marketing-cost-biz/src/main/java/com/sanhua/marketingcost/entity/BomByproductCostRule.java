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
 * BOM 副产品附加结算行规则。
 *
 * <p>本表只表达“是否额外补副产品结算行”。它不参与 BOM 树节点停止下钻、
 * 上卷或排除判断，避免和 {@link BomSettlementRule} 的树节点语义混在一起。
 */
@TableName("lp_bom_byproduct_cost_rule")
public class BomByproductCostRule {

  @TableId(type = IdType.AUTO)
  private Long id;

  private String ruleCode;
  private String ruleName;
  private String ruleCategory;
  private String addConditionType;
  private String settlementRowType;
  private String matchConditionJson;
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
