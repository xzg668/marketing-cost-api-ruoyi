package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BomCostingRecoveryAggregationTest {
  @Test void freezesBothOriginQuantitiesWhileKeepingOnePricingRow() {
    var u9 = recovery("P/__BYPRODUCT__/S", "-0.0094");
    var outsource = recovery("P/VALVE/shape-recovery-S", "-0.04288");
    outsource.setQuotationShapeEvidence(Map.of("kind", "OUTSOURCE_SCRAP_V1"));

    var result = BomCostingRowAggregation.aggregate(List.of(u9, outsource));

    assertThat(result.rows()).containsExactly(u9);
    assertThat(u9.getQtyPerTop()).isEqualByComparingTo("-0.05228");
    assertThat(result.pathAliases()).containsEntry(outsource.getPath(), u9.getPath());
    assertThat(u9.getQuotationShapeEvidence()).containsEntry("kind", "RECOVERY_BREAKDOWN_V1");
    assertThat(u9.getQuotationShapeEvidence().get("lines").toString()).contains("-0.0094", "-0.04288");
  }

  private static BomCostingRow recovery(String path, String quantity) {
    var row = new BomCostingRow();
    row.setPath(path);
    row.setMaterialCode("S");
    row.setSettlementRowType("BYPRODUCT_EXTRA");
    row.setQtyPerTop(new BigDecimal(quantity));
    return row;
  }
}
