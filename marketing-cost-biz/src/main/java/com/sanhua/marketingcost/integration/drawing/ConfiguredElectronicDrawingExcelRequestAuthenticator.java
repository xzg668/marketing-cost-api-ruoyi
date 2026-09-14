package com.sanhua.marketingcost.integration.drawing;

import com.sanhua.marketingcost.config.ElectronicDrawingBomProperties;
import java.net.http.HttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 当前配置型鉴权；后续可用其他实现替换，不改变业务端口。 */
@Component
public class ConfiguredElectronicDrawingExcelRequestAuthenticator
    implements ElectronicDrawingExcelRequestAuthenticator {

  private final ElectronicDrawingBomProperties properties;

  public ConfiguredElectronicDrawingExcelRequestAuthenticator(
      ElectronicDrawingBomProperties properties) {
    this.properties = properties;
  }

  @Override
  public void authenticate(HttpRequest.Builder requestBuilder) {
    if (StringUtils.hasText(properties.getAuthorization())) {
      requestBuilder.header("Authorization", properties.getAuthorization().trim());
    }
  }
}
