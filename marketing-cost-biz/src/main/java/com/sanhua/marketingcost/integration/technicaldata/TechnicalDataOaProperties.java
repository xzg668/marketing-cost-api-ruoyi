package com.sanhua.marketingcost.integration.technicaldata;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.technical-data.oa")
public class TechnicalDataOaProperties {
  private boolean enabled;
  private String baseUrl = "";
  private String publishPath = "/api/tasks";
  private String authorization = "";
  private String callbackSecret = "technical-data-oa-dev-secret-change-in-production";
  private String frontendBaseUrl = "http://localhost:5173";
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 10000;
  private long callbackClockSkewSeconds = 300;

  public boolean isEnabled() { return enabled; }
  public void setEnabled(boolean value) { enabled = value; }
  public String getBaseUrl() { return baseUrl; }
  public void setBaseUrl(String value) { baseUrl = value; }
  public String getPublishPath() { return publishPath; }
  public void setPublishPath(String value) { publishPath = value; }
  public String getAuthorization() { return authorization; }
  public void setAuthorization(String value) { authorization = value; }
  public String getCallbackSecret() { return callbackSecret; }
  public void setCallbackSecret(String value) { callbackSecret = value; }
  public String getFrontendBaseUrl() { return frontendBaseUrl; }
  public void setFrontendBaseUrl(String value) { frontendBaseUrl = value; }
  public int getConnectTimeoutMs() { return connectTimeoutMs; }
  public void setConnectTimeoutMs(int value) { connectTimeoutMs = value; }
  public int getReadTimeoutMs() { return readTimeoutMs; }
  public void setReadTimeoutMs(int value) { readTimeoutMs = value; }
  public long getCallbackClockSkewSeconds() { return callbackClockSkewSeconds; }
  public void setCallbackClockSkewSeconds(long value) { callbackClockSkewSeconds = value; }
}
