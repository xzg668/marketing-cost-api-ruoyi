package com.sanhua.marketingcost.enums;

import java.util.Arrays;
import org.springframework.util.StringUtils;

/**
 * 月度调价执行后端，对齐 lp_monthly_reprice_batch.execution_backend。
 *
 * <p>第一阶段只启用本地 Worker。EASYDATA 仅作为未来扩展点保留，接入时必须遵守：
 * EasyData 按 reprice_no 抓取业务系统已生成的快照，使用业务系统已计算的联动价结果，
 * 只写 stage 表，不直接写正式结果表；业务系统负责校验、确认、权限和审计。
 */
public enum MonthlyRepriceExecutionBackend {
  LOCAL_WORKER("LOCAL_WORKER", true),
  EASYDATA("EASYDATA", false);

  private final String code;
  private final boolean supportedInCurrentPhase;

  MonthlyRepriceExecutionBackend(String code, boolean supportedInCurrentPhase) {
    this.code = code;
    this.supportedInCurrentPhase = supportedInCurrentPhase;
  }

  public String getCode() {
    return code;
  }

  public boolean isSupportedInCurrentPhase() {
    return supportedInCurrentPhase;
  }

  public static MonthlyRepriceExecutionBackend fromCode(String code) {
    String normalized = normalize(code);
    return Arrays.stream(values())
        .filter(backend -> backend.code.equalsIgnoreCase(normalized))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("不支持的月度调价执行后端：" + normalized));
  }

  private static String normalize(String value) {
    if (!StringUtils.hasText(value)) {
      return LOCAL_WORKER.code;
    }
    return value.replace('\u00A0', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }
}
