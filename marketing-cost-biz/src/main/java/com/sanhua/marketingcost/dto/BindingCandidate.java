package com.sanhua.marketingcost.dto;

public class BindingCandidate {
  private String materialCode;
  private String linkedItemImportKey;
  private String tokenName;
  private Long factorIdentityId;
  private Long factorMonthlyPriceId;
  private ResolvedFactorRef sourceRef;

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getLinkedItemImportKey() {
    return linkedItemImportKey;
  }

  public void setLinkedItemImportKey(String linkedItemImportKey) {
    this.linkedItemImportKey = linkedItemImportKey;
  }

  public String getTokenName() {
    return tokenName;
  }

  public void setTokenName(String tokenName) {
    this.tokenName = tokenName;
  }

  public Long getFactorIdentityId() {
    return factorIdentityId;
  }

  public void setFactorIdentityId(Long factorIdentityId) {
    this.factorIdentityId = factorIdentityId;
  }

  public Long getFactorMonthlyPriceId() {
    return factorMonthlyPriceId;
  }

  public void setFactorMonthlyPriceId(Long factorMonthlyPriceId) {
    this.factorMonthlyPriceId = factorMonthlyPriceId;
  }

  public ResolvedFactorRef getSourceRef() {
    return sourceRef;
  }

  public void setSourceRef(ResolvedFactorRef sourceRef) {
    this.sourceRef = sourceRef;
  }
}
