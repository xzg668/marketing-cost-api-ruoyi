package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.BomCostingRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BomCostingRowAggregationTest {

  @Test
  @DisplayName("同一报价料号下同一结算料号多路径出现时按用量累加")
  void aggregatesSameMaterialAcrossBomPaths() {
    BomCostingRow first = row(
        "/1053900000062/1053000303292@30/1053000301622@30/",
        "1053000301622",
        "2",
        LocalDate.of(2026, 4, 24));
    BomCostingRow second = row(
        "/1053900000062/1053000303292@40/1053000301622@40/",
        "1053000301622",
        "2",
        LocalDate.of(2026, 6, 1));

    BomCostingRowAggregation.Result result =
        BomCostingRowAggregation.aggregate(List.of(first, second));

    assertThat(result.rows()).hasSize(1);
    BomCostingRow merged = result.rows().getFirst();
    assertThat(merged.getMaterialCode()).isEqualTo("1053000301622");
    assertThat(merged.getQtyPerParent()).isEqualByComparingTo("4");
    assertThat(merged.getQtyPerTop()).isEqualByComparingTo("4");
    assertThat(result.pathAliases())
        .containsEntry(second.getPath(), first.getPath());
  }

  @Test
  @DisplayName("不同报价产品明细行的同料号不跨行合并")
  void doesNotAggregateAcrossQuoteItems() {
    BomCostingRow first = row("/P/A/", "A", "2", LocalDate.of(2026, 4, 24));
    BomCostingRow second = row("/P/B/", "A", "2", LocalDate.of(2026, 4, 24));
    second.setOaFormItemId(188L);

    BomCostingRowAggregation.Result result =
        BomCostingRowAggregation.aggregate(List.of(first, second));

    assertThat(result.rows()).hasSize(2);
  }

  private static BomCostingRow row(
      String path, String materialCode, String qty, LocalDate rawVersionEffectiveFrom) {
    BomCostingRow row = new BomCostingRow();
    row.setOaNo("FI-SC-020-20260327-037");
    row.setOaFormItemId(187L);
    row.setTopProductCode("1053900000062");
    row.setMaterialCode(materialCode);
    row.setPath(path);
    row.setQtyPerParent(new BigDecimal(qty));
    row.setQtyPerTop(new BigDecimal(qty));
    row.setPeriodMonth("2026-06");
    row.setAsOfDate(LocalDate.of(2026, 6, 17));
    row.setRawVersionEffectiveFrom(rawVersionEffectiveFrom);
    row.setIsCostingRow(1);
    row.setSubtreeCostRequired(0);
    row.setManualModified(0);
    row.setLevel(3);
    return row;
  }
}
