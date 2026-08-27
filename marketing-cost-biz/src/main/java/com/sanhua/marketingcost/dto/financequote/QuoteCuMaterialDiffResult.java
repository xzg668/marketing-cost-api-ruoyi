package com.sanhua.marketingcost.dto.financequote;

import java.math.BigDecimal;
import java.util.List;

/** 单产品双价格准备场景的 Cu 材料费差异计算结果。 */
public record QuoteCuMaterialDiffResult(
    Long costRunVersionId,
    String costRunNo,
    BigDecimal adjustmentAmount,
    int settlementCount,
    int rawComponentCount,
    int cuAffectedSettlementCount,
    List<QuoteCuMaterialDiffItemResult> items) {

  public QuoteCuMaterialDiffResult {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
