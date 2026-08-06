package com.sanhua.marketingcost.service.effectivebom;

/** 单产品最终树查询的稳定业务异常。 */
public class QuoteEffectiveBomQueryException extends RuntimeException {

  private final String code;

  public QuoteEffectiveBomQueryException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
