package com.sanhua.marketingcost.dto;

import com.sanhua.marketingcost.entity.FactorQuoteBaseMapping;

public class FactorQuoteBaseMappingRequest {

  private Long factorIdentityId;
  private Long ruleId;
  private String quoteFieldCode;
  private String quoteFieldName;
  private String variableCode;
  private String matchedKeyword;
  private String matchSource;
  private String confidence;
  private Integer enabled;

  public FactorQuoteBaseMapping toEntity(Long id) {
    FactorQuoteBaseMapping mapping = new FactorQuoteBaseMapping();
    mapping.setId(id);
    mapping.setFactorIdentityId(factorIdentityId);
    mapping.setRuleId(ruleId);
    mapping.setQuoteFieldCode(quoteFieldCode);
    mapping.setQuoteFieldName(quoteFieldName);
    mapping.setVariableCode(variableCode);
    mapping.setMatchedKeyword(matchedKeyword);
    mapping.setMatchSource(matchSource);
    mapping.setConfidence(confidence);
    mapping.setEnabled(enabled);
    return mapping;
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public void setFactorIdentityId(Long factorIdentityId) {
    this.factorIdentityId = factorIdentityId;
  }

  public Long getRuleId() {
    return ruleId;
  }

  public void setRuleId(Long ruleId) {
    this.ruleId = ruleId;
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

  public String getMatchedKeyword() {
    return matchedKeyword;
  }

  public void setMatchedKeyword(String matchedKeyword) {
    this.matchedKeyword = matchedKeyword;
  }

  public String getMatchSource() {
    return matchSource;
  }

  public void setMatchSource(String matchSource) {
    this.matchSource = matchSource;
  }

  public String getConfidence() {
    return confidence;
  }

  public void setConfidence(String confidence) {
    this.confidence = confidence;
  }

  public Integer getEnabled() {
    return enabled;
  }

  public void setEnabled(Integer enabled) {
    this.enabled = enabled;
  }
}
