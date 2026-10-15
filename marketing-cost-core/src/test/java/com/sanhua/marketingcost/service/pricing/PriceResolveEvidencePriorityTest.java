package com.sanhua.marketingcost.service.pricing;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.PriceTypeRoute;
import com.sanhua.marketingcost.enums.MaterialPriceSourceType;
import com.sanhua.marketingcost.enums.PriceTypeEnum;
import com.sanhua.marketingcost.service.MaterialPricePriorityService;
import com.sanhua.marketingcost.service.MaterialPricePriorityService.Scope;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PriceResolveEvidencePriorityTest {
  @Test
  void prioritySnapshotPreservesDecimalInputsAndFreezesActualSettlementType() throws Exception {
    String original = "{\"amount\":1234567890.12345678901234567890,\"rule\":{\"version\":3},\"customerCu\":\"90000.123456789\"}";
    var evidence = new PriceResolveEvidence(7L, "batch", "supplier", "S1", BigDecimal.ONE,
        9L, null, null, false, null, original);
    var config = new MaterialPricePriorityService.Configuration(
        new Scope("COMMERCIAL", "2026-10", "210", "M1"),
        List.of(new MaterialPricePriorityService.Entry(MaterialPriceSourceType.SETTLE_FIXED, 1)), true, "v1");
    var route = new PriceTypeRoute("M1", null, PriceTypeEnum.FIXED, 1, null, null,
        "FINANCE", "结算固定价", "PUBLIC", null, config);
    var snapshot = evidence.withPriority(route);
    var json = new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    var saved = json.readTree(snapshot.inputSnapshotJson());
    assertThat(saved.get("amount").decimalValue()).isEqualByComparingTo("1234567890.12345678901234567890");
    assertThat(saved.at("/rule/version").asInt()).isEqualTo(3);
    assertThat(saved.get("customerCu").asText()).isEqualTo("90000.123456789");
    assertThat(saved.get("selectedPriceType").asText()).isEqualTo("SETTLE_FIXED");
    assertThat(saved.at("/pricePriority/revision").asText()).isEqualTo("v1");
    assertThat(saved.at("/pricePriority/scope/priceOrgCode").asText()).isEqualTo("210");
    assertThat(snapshot.sourcePriceRecordId()).isEqualTo(7L);
    assertThat(evidence.inputSnapshotJson()).isEqualTo(original);
  }

  @Test
  void malformedSourceEvidenceCannotBeSilentlyReplaced() {
    for (String invalid : List.of("[]", "null", "broken")) {
      var evidence = new PriceResolveEvidence(null, null, null, null, null, null, null, null,
          false, null, invalid);
      var config = new MaterialPricePriorityService.Configuration(
          new Scope("COMMERCIAL", "2026-10", "210", "M1"), List.of(), false, "v");
      var route = new PriceTypeRoute("M1", null, PriceTypeEnum.FIXED, 5, null, null,
          "SYSTEM", "固定价", "PUBLIC", null, config);
      assertThatThrownBy(() -> evidence.withPriority(route)).isInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void onlyExplicitlyAbsentRulesAndBandsMayContinueToLowerPriority() {
    for (String code : List.of("MATERIAL_MONTH_PRICE_MISSING", "RANGE_RULE_MISSING", "RANGE_BAND_MISSING")) {
      assertThat(PriceResolveResult.miss(code, "无适用价").hasBlockingFailure()).isFalse();
    }
    for (String code : List.of("LINKED_RULE_ERROR", "FACTOR_INPUT_MISSING", "MATERIAL_RULE_AMBIGUOUS", "FIXED_PRICE_VERSION_CONFLICT", "RANGE_PRICE_MISSING")) {
      assertThat(PriceResolveResult.miss(code, "计算或数据错误").hasBlockingFailure()).isTrue();
    }
  }
}
