package com.sanhua.marketingcost.enums;

import java.util.Arrays;
import org.springframework.util.StringUtils;

/** 普通报价核算执行模式，对齐 cost.run.execution.mode。 */
public enum CostRunExecutionMode {
  API_SYNC("API_SYNC", true),
  TASK_WORKER("TASK_WORKER", true),
  DUAL_COMPARE("DUAL_COMPARE", true);

  private final String code;
  private final boolean executableInCurrentPhase;

  CostRunExecutionMode(String code, boolean executableInCurrentPhase) {
    this.code = code;
    this.executableInCurrentPhase = executableInCurrentPhase;
  }

  public String getCode() {
    return code;
  }

  public boolean isExecutableInCurrentPhase() {
    return executableInCurrentPhase;
  }

  public static CostRunExecutionMode fromCode(String code) {
    String normalized = normalize(code);
    return Arrays.stream(values())
        .filter(mode -> mode.code.equalsIgnoreCase(normalized))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的普通报价核算执行模式：" + normalized));
  }

  private static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return API_SYNC.code;
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
