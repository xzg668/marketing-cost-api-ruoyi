package com.sanhua.marketingcost.service;

import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/** Consumption inside a manufacturing settlement; purchased inputs retain their own unit. */
public final class MakePartConsumption {
  private MakePartConsumption() {}

  public static boolean nonWeightRaw(String process, String unit) {
    return MakePartProcessTypePolicy.PROCESS_TYPE_RAW.equals(process) && unit != null && !unit.isBlank()
        && !Set.of("kg", "kgs", "千克", "公斤", "g", "克").contains(unit.trim().toLowerCase(Locale.ROOT));
  }

  public static boolean nonWeightRaw(MakePartPriceCalcRow row) {
    return nonWeightRaw(row.getItemProcessType(), row.getStockUnit());
  }

  public static BigDecimal rawQuantity(MakePartPriceCalcRow row) {
    if (nonWeightRaw(row)) return row.getQtyPerParent();
    if (MakePartProcessTypePolicy.PROCESS_TYPE_BLANK.equals(row.getItemProcessType())) return BigDecimal.ONE;
    return row.getGrossWeightG() == null ? null : row.getGrossWeightG().movePointLeft(3);
  }

  public static BigDecimal scrapQuantityKg(MakePartPriceCalcRow row) {
    if (Boolean.TRUE.equals(row.getNoScrapConfirmed())) return BigDecimal.ZERO;
    if (row.getRecoveredScrapWeightG() != null) return row.getRecoveredScrapWeightG().movePointLeft(3);
    // Historical snapshots already captured the actual recovery as gross minus net.
    return row.getGrossWeightG() == null || row.getNetWeightG() == null ? null
        : row.getGrossWeightG().subtract(row.getNetWeightG()).movePointLeft(3);
  }
}
