package com.sanhua.marketingcost.integration.oa.oauth;

/** 仅携带可公开的错误，不传递含 code、secret、token 的 HTTP 异常及响应正文。 */
public class OaOAuthException extends RuntimeException {
  private final String code;

  public OaOAuthException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() { return code; }
}
