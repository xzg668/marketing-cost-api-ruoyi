package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import com.sanhua.marketingcost.entity.QuoteTechProduct;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataManufacturingRulesTest {
  private QuoteTechProduct product() {
    var row = new QuoteTechProduct(); row.setOaFormItemId(11L); row.setAccountingMonth("2026-09"); return row;
  }
  private Manufacturing content(List<RawMaterial> rows) {
    return new Manufacturing(rows, new ManufacturingEvidence(11L, "2026-09", 31L, "a".repeat(64), LocalDateTime.now()));
  }
  private RawMaterial row(String key, String parent, String gross, String net, String netUnit, String rawUnit) {
    BigDecimal grossKg = new BigDecimal(gross);
    var evidence = new ManufacturingNodeEvidence("接管 B", "DRAW-B", new BigDecimal("4"),
        new BigDecimal(net), netUnit, "铜管原料", "规格", "MATCHED", List.of(new ManufacturingScrap("SCRAP", "废铜", "kg")));
    return new RawMaterial(key, parent, 31L, "B", "COPPER", "RAW-D",
        TechnicalDataManufacturingUnits.sourceWeightG(new BigDecimal(net), netUnit).movePointLeft(3),
        TechnicalDataManufacturingUnits.purchasingQuantity(grossKg, rawUnit), rawUnit, null, grossKg, new BigDecimal("120"), evidence);
  }

  @Test void convertsAtTheBoundaryWithoutMultiplyingByBomQuantity() {
    var row = row("row-1", "2", "0.012", "10", "g", "kg");
    assertThat(TechnicalDataManufacturingRules.validate(content(List.of(row)), product())).isEmpty();
    var input = TechnicalDataManufacturingRules.calculationInput(row);
    assertThat(input.grossWeightG()).isEqualByComparingTo("12");
    assertThat(input.netWeightG()).isEqualByComparingTo("10");
    assertThat(input.netLengthMm()).isEqualByComparingTo("120");
    assertThat(input.purchasingQuantity()).isEqualByComparingTo("0.012");
    var grams = TechnicalDataManufacturingRules.calculationInput(row("row-2", "3", "0.012", "0.010", "kg", "g"));
    assertThat(grams.netWeightG()).isEqualByComparingTo("10");
    assertThat(grams.purchasingQuantity()).isEqualByComparingTo("12");
  }

  @Test void oneRawPerNodeRejectsDuplicateParentsAndGrossBelowNet() {
    var first = row("row-1", "2", "0.012", "10", "g", "kg");
    var second = row("row-2", "2", "0.015", "10", "g", "kg");
    assertThat(TechnicalDataManufacturingRules.validate(content(List.of(first, second)), product()))
        .anyMatch(message -> message.contains("一种原材料"));
    var underweight = row("row-1", "2", "0.009", "10", "g", "kg");
    assertThat(TechnicalDataManufacturingRules.validate(content(List.of(underweight)), product()))
        .anyMatch(message -> message.contains("毛重不能小于"));
  }

  @Test void unknownUnitsCannotBeGuessedAndLegacySnapshotsDoNotAcquireInventedEvidence() {
    assertThatThrownBy(() -> TechnicalDataManufacturingUnits.sourceWeightG(BigDecimal.TEN, null))
        .hasMessageContaining("单位不明确");
    assertThatThrownBy(() -> TechnicalDataManufacturingUnits.purchasingQuantity(new BigDecimal("0.012"), "米"))
        .hasMessageContaining("不能按净长猜重量");
    var version = new QuoteTechDataVersion();
    version.setManufacturingJson("{\"items\":[{\"itemKey\":\"OLD\",\"parentSourceNodeId\":\"1\",\"sourceVersionId\":1,\"parentMaterialNo\":\"P\",\"rawMaterialNo\":\"RAW\",\"rawMaterialDrawingNo\":null,\"netWeightKg\":0.01,\"quantityPerParent\":0.012,\"unit\":\"kg\",\"sourceReference\":null}]}");
    var codec = new TechnicalDataVersionContentCodec(new ObjectMapper().findAndRegisterModules());
    var legacy = codec.manufacturing(version);
    assertThat(legacy.evidence()).isNull(); assertThat(legacy.items().getFirst().grossWeightKg()).isNull();
    assertThat(codec.manufacturingJson(legacy)).doesNotContain("evidence", "grossWeightKg", "netLengthMm");
  }
}
