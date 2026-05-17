package com.sanhua.marketingcost.dto;

public class StandardBindingDecision {
  private String materialCode;
  private String tokenName;
  private String action;
  private Long standardBindingId;
  private Long oldFactorIdentityId;
  private Long newFactorIdentityId;
  private String reason;
  private BindingCandidate candidate;

  public String getMaterialCode() {
    return materialCode;
  }

  public void setMaterialCode(String materialCode) {
    this.materialCode = materialCode;
  }

  public String getTokenName() {
    return tokenName;
  }

  public void setTokenName(String tokenName) {
    this.tokenName = tokenName;
  }

  public String getAction() {
    return action;
  }

  public void setAction(String action) {
    this.action = action;
  }

  public Long getStandardBindingId() {
    return standardBindingId;
  }

  public void setStandardBindingId(Long standardBindingId) {
    this.standardBindingId = standardBindingId;
  }

  public Long getOldFactorIdentityId() {
    return oldFactorIdentityId;
  }

  public void setOldFactorIdentityId(Long oldFactorIdentityId) {
    this.oldFactorIdentityId = oldFactorIdentityId;
  }

  public Long getNewFactorIdentityId() {
    return newFactorIdentityId;
  }

  public void setNewFactorIdentityId(Long newFactorIdentityId) {
    this.newFactorIdentityId = newFactorIdentityId;
  }

  public String getReason() {
    return reason;
  }

  public void setReason(String reason) {
    this.reason = reason;
  }

  public BindingCandidate getCandidate() {
    return candidate;
  }

  public void setCandidate(BindingCandidate candidate) {
    this.candidate = candidate;
  }
}
