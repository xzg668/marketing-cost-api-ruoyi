package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Row;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel.Source;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.entity.QuoteCostRunVersion;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JjbRecoveryPresentationTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test void displaysTwoOriginsAndConservesTheSavedAmountAtItsPrecision() {
    var result = present("-0.04288");
    assertThat(result).extracting(Row::code).containsExactly("SCRAP", "SCRAP");
    assertThat(result).extracting(Row::quantity).containsExactly("-0.0094", "-0.04288");
    assertThat(result).extracting(Row::amount).containsExactly("-0.454823", "-2.074770");
    assertThat(result).extracting(Row::key).doesNotHaveDuplicates();
    assertThat(result.getLast().name()).contains("VALVE", "委外加工废料", "BLANK");
    assertThat(result.getLast().paths()).containsExactly("P/VALVE/shape-recovery-SCRAP");
  }

  @Test void keepsTheSavedRowWhenThePartsDoNotSumToItsQuantity() {
    assertThat(present("-0.04")).extracting(Row::quantity).containsExactly("-0.05228");
  }

  private List<Row> present(String outsourceQuantity) {
    var row = Row.builder().key("PART:1").code("SCRAP").name("车加工废料").shape("副产品")
        .section("ORDINARY_PARTS").kind("PART").quantity("-0.05228").amount("-2.529593")
        .paths(List.of("P/__BYPRODUCT__/SCRAP")).sources(List.of(new Source("lp_price_prepare_item", 1L, null))).build();
    var preparation = new PricePrepareItem();
    preparation.setId(1L);
    preparation.setResultRefType("MONTHLY_PRICE");
    preparation.setUnitPrice(new BigDecimal("48.38547525"));
    var codec = new MonthlyPriceDocumentCodec(json);
    preparation.setInputSnapshotJson(codec.compact(codec.encode(Map.of("frozenSource", "a".repeat(1500), "materialShapeEvidence", Map.of(
        "kind", "RECOVERY_BREAKDOWN_V1", "lines", List.of(
            Map.of("path", "P/__BYPRODUCT__/SCRAP", "quantity", new BigDecimal("-0.0094")),
            Map.of("path", "P/VALVE/shape-recovery-SCRAP", "quantity", new BigDecimal(outsourceQuantity),
                "outsource", Map.of("parentMaterialCode", "VALVE", "rawMaterialCode", "BLANK"))))))));
    return new JjbRecoveryPresentation(json).apply(new QuoteCostRunVersion(), List.of(row), List.of(preparation));
  }
}
