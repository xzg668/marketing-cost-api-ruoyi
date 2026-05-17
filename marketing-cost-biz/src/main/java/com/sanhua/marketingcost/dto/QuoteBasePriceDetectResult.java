package com.sanhua.marketingcost.dto;

public class QuoteBasePriceDetectResult {

  public static final String STATUS_RECOGNIZED = "RECOGNIZED";
  public static final String STATUS_UNRECOGNIZED = "UNRECOGNIZED";
  public static final String STATUS_CONFLICT = "CONFLICT";

  private Long factorIdentityId;
  private String status;
  private String quoteFieldCode;
  private String quoteFieldName;
  private String variableCode;
  private String matchedKeyword;
  private String message;

  public static QuoteBasePriceDetectResult recognized(
      Long factorIdentityId,
      String quoteFieldCode,
      String quoteFieldName,
      String variableCode,
      String matchedKeyword) {
    QuoteBasePriceDetectResult result = new QuoteBasePriceDetectResult();
    result.setFactorIdentityId(factorIdentityId);
    result.setStatus(STATUS_RECOGNIZED);
    result.setQuoteFieldCode(quoteFieldCode);
    result.setQuoteFieldName(quoteFieldName);
    result.setVariableCode(variableCode);
    result.setMatchedKeyword(matchedKeyword);
    return result;
  }

  public static QuoteBasePriceDetectResult unrecognized(Long factorIdentityId, String message) {
    QuoteBasePriceDetectResult result = new QuoteBasePriceDetectResult();
    result.setFactorIdentityId(factorIdentityId);
    result.setStatus(STATUS_UNRECOGNIZED);
    result.setMessage(message);
    return result;
  }

  public static QuoteBasePriceDetectResult conflict(Long factorIdentityId, String message) {
    QuoteBasePriceDetectResult result = new QuoteBasePriceDetectResult();
    result.setFactorIdentityId(factorIdentityId);
    result.setStatus(STATUS_CONFLICT);
    result.setMessage(message);
    return result;
  }

  public boolean recognized() {
    return STATUS_RECOGNIZED.equals(status);
  }

  public boolean unrecognized() {
    return STATUS_UNRECOGNIZED.equals(status);
  }

  public boolean conflict() {
    return STATUS_CONFLICT.equals(status);
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public void setFactorIdentityId(Long factorIdentityId) {
    this.factorIdentityId = factorIdentityId;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status;
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

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
