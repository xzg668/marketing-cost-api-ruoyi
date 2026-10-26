package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.ProductFees;
import com.sanhua.marketingcost.service.costing.ProductFeeContract;
import com.sanhua.marketingcost.entity.QuoteTechDataVersion;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class TechnicalDataProductFeeRulesTest {
  @ParameterizedTest @NullSource @ValueSource(booleans={true,false})
  void independentOptionalFeesNeverUseTheLegacySwitch(Boolean included) {
    var stored = TechnicalDataProductFeeRules.parse(included, null, "0.30", "/", "1.234567");
    var cost = TechnicalDataProductFeeRules.costingInput(stored);
    assertThat(cost.hasAdditionalFees()).isEqualTo(included);
    assertThat(cost.unitToolingFee()).isNull();
    assertThat(cost.unitMouldFee()).isEqualByComparingTo("0.30");
    assertThat(cost.unitCertificationFee()).isZero();
    assertThat(cost.unitDepreciationFee()).isEqualByComparingTo("1.234567");
    assertThat(cost.encodingVersion()).isEqualTo(2);
    assertThat(cost.currency()).isEqualTo("CNY");
  }

  @Test void nullAndExplicitNoneRoundTripWithoutInventingZeroOrChangingOldEncoding() {
    var codec = new TechnicalDataVersionContentCodec(new com.fasterxml.jackson.databind.ObjectMapper());
    var version = new QuoteTechDataVersion();
    var fees = TechnicalDataProductFeeRules.parse(null, null, "/", " ", "0");
    version.setProductFeesJson(codec.productFeesJson(fees));
    assertThat(codec.productFees(version)).isEqualTo(fees);
    assertThat(TechnicalDataProductFeeRules.display(fees.unitToolingFee())).isNull();
    assertThat(TechnicalDataProductFeeRules.display(fees.unitMouldFee())).isEqualTo("/");
    assertThat(TechnicalDataProductFeeRules.validate(null)).isEmpty();
    assertThat(TechnicalDataProductFeeRules.costingInput(null)).isNull();
    var empty = TechnicalDataProductFeeRules.costingInput(TechnicalDataProductFeeRules.parse(true, null, "", " ", null));
    assertThat(empty.unitToolingFee()).isNull();
    assertThat(empty.unitMouldFee()).isNull();
    assertThat(empty.unitCertificationFee()).isNull();
    assertThat(empty.unitDepreciationFee()).isNull();
    String old = "{\"includesNewToolingMouldCertificationFee\":false,\"unitToolingFee\":0,\"unitMouldFee\":0.3,\"unitCertificationFee\":null,\"currency\":\"CNY\"}";
    version.setProductFeesJson(old);
    var historical = TechnicalDataProductFeeRules.costingInput(codec.productFees(version));
    assertThat(historical.unitDepreciationFee()).isNull();
    assertThat(historical.encodingVersion()).isNull();
    assertThat(ProductFeeContract.state(historical.unitToolingFee(),historical.encodingVersion())).isEqualTo(ProductFeeContract.State.LEGACY);
    assertThat(version.getProductFeesJson()).isEqualTo(old);
  }

  @ParameterizedTest @ValueSource(strings={"-1","NaN","Infinity","+1","1e2","1,000","0.0000000","1.1234567","1000000000000","0000000000000"})
  void invalidAmountsAreRejectedForEveryFee(String value) {
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(null,value,null,null,null)).hasMessageContaining("工装夹具费");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(null,null,value,null,null)).hasMessageContaining("模具费");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(null,null,null,value,null)).hasMessageContaining("认证费");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.parse(null,null,null,null,value)).hasMessageContaining("折旧费");
  }

  @Test void costAmountJsonPreservesTheCompleteAllowedDecimalForBrowserReaders() throws Exception {
    var dto = new com.sanhua.marketingcost.dto.CostRunCostItemDto();
    dto.setCostCode("OTHER_EXP_TECH_DEPRECIATION");
    dto.setAmount(new BigDecimal("999999999999.999999"));
    var json = new com.fasterxml.jackson.databind.ObjectMapper();
    var value = json.readTree(json.writeValueAsString(dto));
    assertThat(value.path("amount").isTextual()).isTrue();
    assertThat(value.path("amount").asText()).isEqualTo("999999999999.999999");
    assertThat(json.readValue(json.writeValueAsString(dto),com.sanhua.marketingcost.dto.CostRunCostItemDto.class).getAmount()).isEqualByComparingTo(dto.getAmount());
    var trace = new com.sanhua.marketingcost.dto.costruntrace.CostRunTraceDetailDto();
    trace.setAmount(dto.getAmount());
    var traceRow = new com.sanhua.marketingcost.dto.costruntrace.CostRunTraceListItemDto();
    traceRow.setAmount(dto.getAmount());
    for (Object row : java.util.List.of(trace, traceRow)) {
      assertThat(json.readTree(json.writeValueAsString(row)).path("amount").asText()).isEqualTo("999999999999.999999");
      assertThat(json.readTree(json.writeValueAsString(row)).path("amount").isTextual()).isTrue();
    }
  }

  @Test void largeV2FeesUseExactJsonTextWhileLegacyAndSmallAmountsKeepTheirShape() throws Exception {
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var amount = new BigDecimal("999999999900.999999");
    var current = new ProductFees(null, amount, amount, amount, "CNY", amount, 2);
    for (Object value : java.util.List.of(current, TechnicalDataProductFeeRules.costingInput(current))) {
      var json = mapper.readTree(mapper.writeValueAsString(value));
      for (String field : java.util.List.of("unitToolingFee", "unitMouldFee", "unitCertificationFee", "unitDepreciationFee")) {
        assertThat(json.path(field).isTextual()).isTrue();
        assertThat(json.path(field).asText()).isEqualTo(amount.toPlainString());
      }
    }
    assertThat(mapper.readValue(mapper.writeValueAsString(current), ProductFees.class)).isEqualTo(current);
    var legacy = new ProductFees(false, amount, null, null, "CNY", null, null);
    var oldJson = mapper.readTree(mapper.writeValueAsString(legacy));
    assertThat(oldJson.path("unitToolingFee").isNumber()).isTrue();
    assertThat(oldJson.has("encodingVersion")).isFalse();
    assertThat(oldJson.has("unitDepreciationFee")).isFalse();
    var small = TechnicalDataProductFeeRules.parse(null, "1.234567", "0", null, null);
    assertThat(mapper.readTree(mapper.writeValueAsString(small)).path("unitToolingFee").isNumber()).isTrue();
    assertThat(mapper.readTree(mapper.writeValueAsString(small)).path("unitMouldFee").isNumber()).isTrue();
  }

  @Test void storedCurrencyEncodingAndAmountsRemainValidated() {
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.costingInput(new ProductFees(null,null,null,null,"USD",null,2))).hasMessageContaining("人民币");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.costingInput(new ProductFees(null,null,null,null,"CNY",null,3))).hasMessageContaining("编码版本");
    assertThatThrownBy(() -> TechnicalDataProductFeeRules.costingInput(new ProductFees(null,null,null,null,"CNY",new BigDecimal("-1"),2))).hasMessageContaining("折旧费");
    var all = TechnicalDataProductFeeRules.parse(false,"1","2","3","4");
    assertThat(all.unitToolingFee().add(all.unitMouldFee()).add(all.unitCertificationFee()).add(all.unitDepreciationFee())).isEqualByComparingTo("10");
  }
}
