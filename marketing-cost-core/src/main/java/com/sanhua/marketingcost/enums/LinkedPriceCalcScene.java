package com.sanhua.marketingcost.enums;

import java.util.Optional;

/** 联动价计算场景，决定公式变量从哪里取值。 */
public enum LinkedPriceCalcScene {
  /** 正常报价：Cu/Zn/Sn 等变量使用 OA 单头锁价。 */
  QUOTE("QUOTE", "正常报价", LinkedPriceFactorSource.OA_LOCKED),
  /** 月度调价：同一套公式，但变量使用影响因素月度价或可选调价批次价。 */
  MONTHLY_ADJUST("MONTHLY_ADJUST", "月度调价", LinkedPriceFactorSource.MONTHLY_FACTOR);

  private final String code;
  private final String label;
  private final LinkedPriceFactorSource defaultFactorSource;

  LinkedPriceCalcScene(
      String code, String label, LinkedPriceFactorSource defaultFactorSource) {
    this.code = code;
    this.label = label;
    this.defaultFactorSource = defaultFactorSource;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public LinkedPriceFactorSource getDefaultFactorSource() {
    return defaultFactorSource;
  }

  public boolean requiresOaNo() {
    return this == QUOTE;
  }

  public boolean requiresAdjustBatchId() {
    return false;
  }

  public static Optional<LinkedPriceCalcScene> fromCode(String code) {
    if (code == null || code.trim().isEmpty()) {
      return Optional.empty();
    }
    String normalized = code.trim();
    for (LinkedPriceCalcScene scene : values()) {
      if (scene.code.equalsIgnoreCase(normalized) || scene.name().equalsIgnoreCase(normalized)) {
        return Optional.of(scene);
      }
    }
    return Optional.empty();
  }
}
