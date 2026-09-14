package com.sanhua.marketingcost.service;

import java.util.List;

/** Deterministic business/data-integrity failure while selecting technical costing input. */
public class EffectiveTechnicalDataException extends RuntimeException {
  private final String errorCode;
  private final Long oaFormItemId;
  private final String accountingMonth;
  private final List<String> modules;

  public EffectiveTechnicalDataException(
      String errorCode,
      Long oaFormItemId,
      String accountingMonth,
      List<String> modules,
      String message) {
    super(message);
    this.errorCode = errorCode;
    this.oaFormItemId = oaFormItemId;
    this.accountingMonth = accountingMonth;
    this.modules = modules == null ? List.of() : List.copyOf(modules);
  }

  public String errorCode() {
    return errorCode;
  }

  public Long oaFormItemId() {
    return oaFormItemId;
  }

  public String accountingMonth() {
    return accountingMonth;
  }

  public List<String> modules() {
    return modules;
  }
}
