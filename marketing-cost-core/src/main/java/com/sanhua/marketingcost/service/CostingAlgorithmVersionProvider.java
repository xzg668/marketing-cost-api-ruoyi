package com.sanhua.marketingcost.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 提供当前成本算法版本；仅在影响成本金额的算法或规则实现变更时升级。 */
@Component
public class CostingAlgorithmVersionProvider {

  public static final String DEFAULT_VERSION = "COST_V3";

  private final String currentVersion;

  public CostingAlgorithmVersionProvider(
      @Value("${cost.run.algorithm-version:" + DEFAULT_VERSION + "}") String currentVersion) {
    if (!StringUtils.hasText(currentVersion)) {
      throw new IllegalArgumentException("成本算法版本不能为空");
    }
    String normalized = currentVersion.trim();
    if (normalized.length() > 64) {
      throw new IllegalArgumentException("成本算法版本长度不能超过64个字符");
    }
    this.currentVersion = normalized;
  }

  public String currentVersion() {
    return currentVersion;
  }
}
