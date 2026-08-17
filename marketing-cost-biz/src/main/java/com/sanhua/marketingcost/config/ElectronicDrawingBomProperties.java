package com.sanhua.marketingcost.config;

import com.sanhua.marketingcost.integration.drawing.ElectronicDrawingMode;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 电子图库当前有效 BOM 查询配置；默认关闭并失败关闭，不能伪造回取成功。 */
@Component
@ConfigurationProperties(prefix = "quote.collaboration.electronic-drawing")
public class ElectronicDrawingBomProperties {

  private ElectronicDrawingMode mode = ElectronicDrawingMode.DISABLED;
  private String baseUrl;
  private String currentBomPath = "/api/v1/boms/current";
  private String authorization;
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 10000;

  public ElectronicDrawingMode getMode() {
    return mode;
  }

  public void setMode(ElectronicDrawingMode mode) {
    this.mode = mode == null ? ElectronicDrawingMode.DISABLED : mode;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getCurrentBomPath() {
    return currentBomPath;
  }

  public void setCurrentBomPath(String currentBomPath) {
    this.currentBomPath = currentBomPath;
  }

  public String getAuthorization() {
    return authorization;
  }

  public void setAuthorization(String authorization) {
    this.authorization = authorization;
  }

  public int getConnectTimeoutMs() {
    return connectTimeoutMs;
  }

  public void setConnectTimeoutMs(int connectTimeoutMs) {
    this.connectTimeoutMs = positive(connectTimeoutMs, 3000);
  }

  public int getReadTimeoutMs() {
    return readTimeoutMs;
  }

  public void setReadTimeoutMs(int readTimeoutMs) {
    this.readTimeoutMs = positive(readTimeoutMs, 10000);
  }

  private static int positive(int value, int fallback) {
    return value > 0 ? value : fallback;
  }
}
