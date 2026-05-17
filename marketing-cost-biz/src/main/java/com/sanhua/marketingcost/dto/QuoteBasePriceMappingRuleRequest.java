package com.sanhua.marketingcost.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.QuoteBasePriceMappingRule;
import java.util.ArrayList;
import java.util.List;

public class QuoteBasePriceMappingRuleRequest {

  private String businessUnitType;
  private String quoteFieldCode;
  private String quoteFieldName;
  private String variableCode;
  private List<String> matchKeywords = new ArrayList<>();
  private String matchMode;
  private Integer priority;
  private Integer enabled;
  private String remark;

  public QuoteBasePriceMappingRule toEntity(ObjectMapper objectMapper) {
    QuoteBasePriceMappingRule rule = new QuoteBasePriceMappingRule();
    rule.setBusinessUnitType(businessUnitType);
    rule.setQuoteFieldCode(quoteFieldCode);
    rule.setQuoteFieldName(quoteFieldName);
    rule.setVariableCode(variableCode);
    rule.setMatchKeywordsJson(writeKeywords(objectMapper));
    rule.setMatchMode(matchMode);
    rule.setPriority(priority);
    rule.setEnabled(enabled);
    rule.setRemark(remark);
    return rule;
  }

  private String writeKeywords(ObjectMapper objectMapper) {
    try {
      return objectMapper.writeValueAsString(matchKeywords == null ? List.of() : matchKeywords);
    } catch (JsonProcessingException ex) {
      throw new IllegalArgumentException("匹配关键词序列化失败: " + ex.getMessage());
    }
  }

  public String getBusinessUnitType() {
    return businessUnitType;
  }

  public void setBusinessUnitType(String businessUnitType) {
    this.businessUnitType = businessUnitType;
  }

  public String getQuoteFieldCode() {
    return quoteFieldCode;
  }

  public void setQuoteFieldCode(String quoteFieldCode) {
    this.quoteFieldCode = quoteFieldCode;
  }

  public String getQuoteFieldName() {
    return quoteFieldName;
  }

  public void setQuoteFieldName(String quoteFieldName) {
    this.quoteFieldName = quoteFieldName;
  }

  public String getVariableCode() {
    return variableCode;
  }

  public void setVariableCode(String variableCode) {
    this.variableCode = variableCode;
  }

  public List<String> getMatchKeywords() {
    return matchKeywords;
  }

  public void setMatchKeywords(List<String> matchKeywords) {
    this.matchKeywords = matchKeywords;
  }

  public String getMatchMode() {
    return matchMode;
  }

  public void setMatchMode(String matchMode) {
    this.matchMode = matchMode;
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

  public String getRemark() {
    return remark;
  }

  public void setRemark(String remark) {
    this.remark = remark;
  }
}
