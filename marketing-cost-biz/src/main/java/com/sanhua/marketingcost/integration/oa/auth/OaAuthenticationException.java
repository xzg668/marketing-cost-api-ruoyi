package com.sanhua.marketingcost.integration.oa.auth;

/** 仅携带操作、HTTP 状态或 OA 错误码，不携带请求凭证和响应原文。 */
public class OaAuthenticationException extends RuntimeException {
  public OaAuthenticationException(String message) {
    super(message);
  }
}
