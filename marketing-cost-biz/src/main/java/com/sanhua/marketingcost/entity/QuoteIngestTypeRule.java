package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("lp_quote_ingest_type_rule")
public class QuoteIngestTypeRule {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String ruleCode;
  private String ruleName;
  private Integer priority;
  private Integer enabled;
  private String sourceType;
  private String processCode;
  private String processNameKeyword;
  private String applicantUnitKeyword;
  private String businessTypeKeyword;
  private String productAttrKeyword;
  private Integer firstQuoteFlag;
  private String targetBusinessUnitType;
  private String targetQuoteScenario;
  private Integer confidence;
  private String remark;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

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

  public String getSourceType() {
    return sourceType;
  }

  public void setSourceType(String sourceType) {
    this.sourceType = sourceType;
  }

  public String getProcessCode() {
    return processCode;
  }

  public void setProcessCode(String processCode) {
    this.processCode = processCode;
  }

  public String getProcessNameKeyword() {
    return processNameKeyword;
  }

  public void setProcessNameKeyword(String processNameKeyword) {
    this.processNameKeyword = processNameKeyword;
  }

  public String getApplicantUnitKeyword() {
    return applicantUnitKeyword;
  }

  public void setApplicantUnitKeyword(String applicantUnitKeyword) {
    this.applicantUnitKeyword = applicantUnitKeyword;
  }

  public String getBusinessTypeKeyword() {
    return businessTypeKeyword;
  }

  public void setBusinessTypeKeyword(String businessTypeKeyword) {
    this.businessTypeKeyword = businessTypeKeyword;
  }

  public String getProductAttrKeyword() {
    return productAttrKeyword;
  }

  public void setProductAttrKeyword(String productAttrKeyword) {
    this.productAttrKeyword = productAttrKeyword;
  }

  public Integer getFirstQuoteFlag() {
    return firstQuoteFlag;
  }

  public void setFirstQuoteFlag(Integer firstQuoteFlag) {
    this.firstQuoteFlag = firstQuoteFlag;
  }

  public String getTargetBusinessUnitType() {
    return targetBusinessUnitType;
  }

  public void setTargetBusinessUnitType(String targetBusinessUnitType) {
    this.targetBusinessUnitType = targetBusinessUnitType;
  }

  public String getTargetQuoteScenario() {
    return targetQuoteScenario;
  }

  public void setTargetQuoteScenario(String targetQuoteScenario) {
    this.targetQuoteScenario = targetQuoteScenario;
  }

  public Integer getConfidence() {
    return confidence;
  }

  public void setConfidence(Integer confidence) {
    this.confidence = confidence;
  }

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(LocalDateTime createdAt) {
    this.createdAt = createdAt;
  }

  public LocalDateTime getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(LocalDateTime updatedAt) {
    this.updatedAt = updatedAt;
  }
}
