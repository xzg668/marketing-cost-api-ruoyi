package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.entity.monthly.MonthlySupplierRatioInput;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class MonthlySupplierRatioSnapshotTest {
  private final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());

  @Test
  void originalLinkedSnapshotRoundTripsTheActualMapperResultAsItsDeclaredSupplierType() {
    var ratio = ratio();
    var source = new MonthlyOriginalLinkedPriceSource.Source(
        "2026-10", null, List.of(), null, List.of(), List.of(ratio), "PRIMARY", null);
    var snapshot = new MonthlyOriginalLinkedPriceSource.Snapshot(1, "LINKED", source, null);

    var encoded = codec.encode(snapshot);
    var restored = codec.decode(encoded, MonthlyOriginalLinkedPriceSource.Snapshot.class);

    assertThat(restored.source().ratios()).singleElement().satisfies(saved -> {
      assertThat(saved.getMaterialCode()).isEqualTo("332030278");
      assertThat(saved.getSupplierCode()).isEqualTo("S001250");
      assertThat(saved.getSupplyRatio()).isEqualByComparingTo("0.8");
    });
    assertThat(codec.unwrap(encoded)).doesNotContain("requestedMaterialCode");
    assertThat(ratio.getRequestedMaterialCode()).isEqualTo("332030278");
  }

  @Test
  void fixedSnapshotAlsoKeepsSupplierEvidenceWithoutTheQueryRoutingAlias() {
    var source = new MonthlyFixedPriceSource.Source(null, List.of(), List.of(ratio()), "PRIMARY");
    var restored = codec.decode(codec.encode(source), MonthlyFixedPriceSource.Source.class);
    assertThat(restored.ratios()).singleElement()
        .extracting(SupplierSupplyRatio::getSupplyRatio).isEqualTo(new BigDecimal("0.8"));
  }

  @Test
  void unexpectedBusinessFieldsStillFailStrictDecoding() {
    assertThatThrownBy(() -> codec.decode(
        codec.wrap("{\"supplierCode\":\"S001250\",\"unexpectedPrice\":100}"), SupplierSupplyRatio.class))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Invalid monthly price document");
  }

  private MonthlySupplierRatioInput ratio() {
    var ratio = new MonthlySupplierRatioInput();
    ratio.setId(1L);
    ratio.setRequestedMaterialCode("332030278");
    ratio.setMaterialCode("332030278");
    ratio.setSupplierCode("S001250");
    ratio.setSupplyRatio(new BigDecimal("0.8"));
    return ratio;
  }
}
