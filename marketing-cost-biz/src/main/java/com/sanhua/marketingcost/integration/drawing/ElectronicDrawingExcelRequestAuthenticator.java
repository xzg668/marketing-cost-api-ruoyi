package com.sanhua.marketingcost.integration.drawing;

import java.net.http.HttpRequest;

/** 电子图库鉴权扩展点；正式接口交付后可替换为签名、令牌刷新或网关认证。 */
@FunctionalInterface
public interface ElectronicDrawingExcelRequestAuthenticator {

  void authenticate(HttpRequest.Builder requestBuilder);
}
