package com.sanhua.marketingcost.enums;

import java.util.Locale;

/**
 * 报价产品成本版本状态。
 *
 * <p>{@code CONFIRMED}/{@code VOIDED} 仅用于兼容历史数据；新自动流水线写入
 * {@code RUNNING}/{@code SUCCESS}/{@code HISTORY}。
 */
public enum QuoteCostRunStatus {
  RUNNING,
  SUCCESS,
  HISTORY,
  TRIAL,
  CONFIRMED,
  VOIDED,
  STALE;

  public static boolean isCurrentSuccess(String value) {
    QuoteCostRunStatus status = parse(value);
    return status == SUCCESS || status == CONFIRMED;
  }

  public static boolean isHistorical(String value) {
    QuoteCostRunStatus status = parse(value);
    return status == HISTORY || status == VOIDED || status == STALE;
  }

  public static boolean isInProgress(String value) {
    QuoteCostRunStatus status = parse(value);
    return status == RUNNING || status == TRIAL;
  }

  private static QuoteCostRunStatus parse(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
