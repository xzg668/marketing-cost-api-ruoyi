package com.sanhua.marketingcost.dto;

public class MetalBasePricePolicyResponse {

  private String variableCode;
  private String metalName;
  private String quoteFieldCode;
  private String quoteFieldName;
  private String pricePolicy;

  public MetalBasePricePolicyResponse() {
  }

  public MetalBasePricePolicyResponse(
      String variableCode,
      String metalName,
      String quoteFieldCode,
      String quoteFieldName,
      String pricePolicy) {
    this.variableCode = variableCode;
    this.metalName = metalName;
    this.quoteFieldCode = quoteFieldCode;
    this.quoteFieldName = quoteFieldName;
    this.pricePolicy = pricePolicy;
  }

  public String getVariableCode() {
    return variableCode;
  }

  public void setVariableCode(String variableCode) {
    this.variableCode = variableCode;
  }

  public String getMetalName() {
    return metalName;
  }

  public void setMetalName(String metalName) {
    this.metalName = metalName;
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

  public String getPricePolicy() {
    return pricePolicy;
  }

  public void setPricePolicy(String pricePolicy) {
    this.pricePolicy = pricePolicy;
  }
}
