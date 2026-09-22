package com.sanhua.marketingcost.integration.technicaldata;

/** 网络或回执不确定时，必须先查原请求，不能当成明确拒绝而开放编辑。 */
public class OaDeliveryUnknownException extends RuntimeException {
  public OaDeliveryUnknownException(String message) { super(message); }
}
