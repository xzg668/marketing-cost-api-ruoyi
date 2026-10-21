package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.service.pricing.settlement.SettlementPriceType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SettlementCalculationEvidenceTest {
  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

  @Test void preservesFullDecimalPrecisionBeyondBinaryFloatingPoint() throws Exception {
    var prepared = prepared();
    var captured = new SettlementCalculationEvidence(json).read(prepared);
    assertThat(captured.path("unitPrice").decimalValue()).isEqualByComparingTo("999999999999.123456789012");
    assertThat(captured.path("rule").path("givenPrice").decimalValue()).isEqualByComparingTo("999999999999.123456789012");
  }

  @ParameterizedTest
  @CsvSource({"sourceTable,lp_price_fixed_item", "accountingMonth,2026-12", "consumerOrgCode,220", "mode,LINKED_FORMULA"})
  void refusesConflictingNamespaceScopeOrCalculationMode(String field, String value) throws Exception {
    var prepared = prepared();
    var captured = (com.fasterxml.jackson.databind.node.ObjectNode) json.reader()
        .with(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .readTree(prepared.getInputSnapshotJson());
    captured.put(field, value);
    prepared.setInputSnapshotJson(captured.toString());
    assertThatThrownBy(() -> new SettlementCalculationEvidence(json).read(prepared))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("结算取价底稿校验失败");
  }

  private PricePrepareItem prepared() throws Exception {
    var rule = new SettlementPriceRecord();
    rule.setId(7L);
    rule.setBusinessUnitType("COMMERCIAL");
    rule.setMaterialCode("PRECISE");
    rule.setPriceType(SettlementPriceType.SETTLE_FIXED);
    rule.setPriceMonth("2026-10");
    rule.setRevisionNo(1);
    rule.setActive(true);
    rule.setGivenPrice(new BigDecimal("999999999999.123456789012"));
    var captured = json.createObjectNode();
    captured.put("sourceTable", "lp_settlement_price");
    captured.put("resultRefType", "SETTLEMENT_PRICE");
    captured.put("sourceRecordId", 7);
    captured.put("priceType", "SETTLE_FIXED");
    captured.put("accountingMonth", "2026-11");
    captured.put("consumerOrgCode", "210");
    captured.put("mode", "FIXED_GIVEN");
    captured.put("calculatorVersion", "SETTLEMENT_PRICE_V1");
    captured.put("unitPrice", rule.getGivenPrice());
    captured.set("rule", json.valueToTree(rule));
    var prepared = new PricePrepareItem();
    prepared.setMaterialCode("PRECISE");
    prepared.setBusinessUnitType("COMMERCIAL");
    prepared.setPeriodMonth("2026-11");
    prepared.setPriceOrgCode("210");
    prepared.setResultRefId(7L);
    prepared.setUnitPrice(rule.getGivenPrice().setScale(8, RoundingMode.HALF_UP));
    prepared.setInputSnapshotJson(captured.toString());
    return prepared;
  }
}
