package com.sanhua.marketingcost.service.ingest;

public class QuotePdfParseException extends QuoteIngestException {
  private final String code;

  public QuotePdfParseException(String code, String message) {
    super(message);
    this.code = code;
  }

  public QuotePdfParseException(String code, String message, Throwable cause) {
    super(message);
    initCause(cause);
    this.code = code;
  }

  public String getCode() {
    return code;
  }
}
