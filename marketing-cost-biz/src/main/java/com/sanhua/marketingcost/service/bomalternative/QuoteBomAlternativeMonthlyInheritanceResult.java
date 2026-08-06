package com.sanhua.marketingcost.service.bomalternative;

import com.sanhua.marketingcost.entity.QuoteBomAlternativeSelection;
import java.util.List;

/** 月度选择继承结果；冻结时selections只包含最终树中实际可达的替代组。 */
public record QuoteBomAlternativeMonthlyInheritanceResult(
    boolean frozen,
    boolean inherited,
    Long monthlySnapshotId,
    String buildBatchId,
    List<QuoteBomAlternativeSelection> selections) {

  public QuoteBomAlternativeMonthlyInheritanceResult {
    selections = selections == null ? List.of() : List.copyOf(selections);
  }

  public static QuoteBomAlternativeMonthlyInheritanceResult notFrozen() {
    return new QuoteBomAlternativeMonthlyInheritanceResult(
        false, false, null, null, List.of());
  }

  public boolean provisional() {
    return !frozen && monthlySnapshotId != null && buildBatchId != null;
  }
}
