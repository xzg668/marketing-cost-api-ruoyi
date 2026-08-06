package com.sanhua.marketingcost.service.bomalternative;

/** 带稳定错误码的报价 BOM 替代选择业务异常。 */
public final class QuoteBomAlternativeSelectionException
    extends RuntimeException {

  private final String code;

  public QuoteBomAlternativeSelectionException(String code, String message) {
    super(message);
    this.code = code;
  }

  public QuoteBomAlternativeSelectionException(
      String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
