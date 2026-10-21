package com.sanhua.marketingcost.service.pricing.settlement;

import java.math.BigDecimal;

/** 原稿重量仅作证据；正式计算必须使用完整且经财务确认的维护值。单位均为克。 */
public record SettlementWeights(
    BigDecimal originalCopperG, BigDecimal originalZincG,
    BigDecimal copperG, BigDecimal zincG, boolean confirmed, String confirmationNote) {
  public SettlementWeights {
    nonnegative(originalCopperG);
    nonnegative(originalZincG);
    nonnegative(copperG);
    nonnegative(zincG);
    if (confirmed && (copperG == null || zincG == null))
      throw new IllegalArgumentException("确认重量必须同时提供铜重和锌重，明确为零请填写 0");
    if (confirmed) confirmationNote = SettlementScope.required(confirmationNote, "重量确认依据", 1024);
  }

  public boolean completeAndConfirmed() { return confirmed && copperG != null && zincG != null; }

  private static void nonnegative(BigDecimal value) {
    if (value != null && value.signum() < 0) throw new IllegalArgumentException("铜锌重量不能为负数");
  }
}
