package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.service.collaboration.scan.CollaborationPriceScanResult;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-13 真实缺价稳定指纹")
class CollaborationPriceGapCommandFactoryTest {

  @Test
  void sameBusinessPositionReusesFingerprintWhenTransientSourceIdChanges() {
    GapUpsertCommand first = CollaborationPriceGapCommandFactory.create(275L, "P-1", gap(11L,
        "/P-1/M-1/RAW-1/", "1"));
    GapUpsertCommand rescanned = CollaborationPriceGapCommandFactory.create(275L, "P-1", gap(99L,
        "/P-1/M-1/RAW-1/", "2"));
    GapUpsertCommand anotherPath = CollaborationPriceGapCommandFactory.create(275L, "P-1", gap(99L,
        "/P-1/M-2/RAW-1/", "2"));

    assertThat(rescanned.gapFingerprint()).isEqualTo(first.gapFingerprint());
    assertThat(rescanned.sourceId()).isEqualTo(99L);
    assertThat(rescanned.bomQuantity()).isEqualByComparingTo("2");
    assertThat(anotherPath.gapFingerprint()).isNotEqualTo(first.gapFingerprint());
  }

  @Test
  void quoteItemAndMonthArePartOfBusinessFingerprint() {
    GapUpsertCommand baseline = CollaborationPriceGapCommandFactory.create(
        275L, "P-1", gap(11L, "/P-1/M-1/RAW-1/", "1"));
    GapUpsertCommand anotherItem = CollaborationPriceGapCommandFactory.create(
        276L, "P-1", gap(11L, "/P-1/M-1/RAW-1/", "1"));
    CollaborationPriceScanResult.PriceGap nextMonth = new CollaborationPriceScanResult.PriceGap(
        "RAW-1", "MISSING_PRICE", "MAINTAIN_PRICE", "缺价", "lp_price_linked_item",
        null, "ELECTRONIC_DRAWING_BOM", 11L, "NODE:11", "/P-1/M-1/RAW-1/",
        "原材料铜管", "TP2", "TP2-952", "RAW", new BigDecimal("1"), "kg",
        "2026-09", "210");

    assertThat(anotherItem.gapFingerprint()).isNotEqualTo(baseline.gapFingerprint());
    assertThat(CollaborationPriceGapCommandFactory.create(275L, "P-1", nextMonth)
        .gapFingerprint()).isNotEqualTo(baseline.gapFingerprint());
  }

  private CollaborationPriceScanResult.PriceGap gap(Long sourceId, String path, String quantity) {
    return new CollaborationPriceScanResult.PriceGap(
        "RAW-1", "MISSING_PRICE", "MAINTAIN_PRICE", "缺价", "lp_price_linked_item",
        null, "ELECTRONIC_DRAWING_BOM", sourceId, "NODE:" + sourceId, path,
        "原材料铜管", "TP2", "TP2-952", "RAW", new BigDecimal(quantity), "kg",
        "2026-08", "210");
  }
}
