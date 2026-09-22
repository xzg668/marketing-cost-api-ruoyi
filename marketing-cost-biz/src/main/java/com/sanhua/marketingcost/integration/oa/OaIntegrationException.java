package com.sanhua.marketingcost.integration.oa;

import org.springframework.http.HttpStatus;

/** 对外只返回明确的业务错误，数据库异常和报文内容不进入错误回执。 */
public class OaIntegrationException extends RuntimeException {
  private final HttpStatus httpStatus;
  private final String stage;
  private final String code;

  public OaIntegrationException(HttpStatus httpStatus, String stage, String code, String message) {
    super(message);
    this.httpStatus = httpStatus;
    this.stage = stage;
    this.code = code;
  }

  public HttpStatus httpStatus() { return httpStatus; }
  public String stage() { return stage; }
  public String code() { return code; }

  public static OaIntegrationException invalid(String code, String message) {
    return new OaIntegrationException(HttpStatus.UNPROCESSABLE_ENTITY, "MAPPING", code, message);
  }

  public static OaIntegrationException conflict(String code, String message) {
    return new OaIntegrationException(HttpStatus.CONFLICT, "BUSINESS", code, message);
  }
}
