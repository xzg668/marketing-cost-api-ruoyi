package com.sanhua.marketingcost.integration.drawing;

/** 保留具体失败类别与 HTTP 原始响应；异常文字不回显可能包含签名链接的正文。 */
public class DrawingVerificationException extends RuntimeException {
  public enum Code { NOT_CONFIGURED, HTTP_ERROR, TIMEOUT, INTERRUPTED, TRANSPORT_ERROR, INVALID_RESPONSE }
  private final Code code;
  private final Integer httpStatus;
  private final String responseBody;

  public DrawingVerificationException(Code code, String message, Integer httpStatus,
      String responseBody, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.httpStatus = httpStatus;
    this.responseBody = responseBody;
  }

  public Code getCode() { return code; }
  public Integer getHttpStatus() { return httpStatus; }
  public String getResponseBody() { return responseBody; }
}
