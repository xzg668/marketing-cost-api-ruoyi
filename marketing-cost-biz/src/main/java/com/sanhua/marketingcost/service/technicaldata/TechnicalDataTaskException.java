package com.sanhua.marketingcost.service.technicaldata;

public class TechnicalDataTaskException extends RuntimeException {
  private final TechnicalDataTaskErrorCode code;

  public TechnicalDataTaskException(TechnicalDataTaskErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public TechnicalDataTaskErrorCode code() {
    return code;
  }
}
