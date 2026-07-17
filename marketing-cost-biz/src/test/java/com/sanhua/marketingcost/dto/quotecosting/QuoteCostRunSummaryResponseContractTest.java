package com.sanhua.marketingcost.dto.quotecosting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("FCQ-10 单产品成本汇总接口契约")
class QuoteCostRunSummaryResponseContractTest {

  @Test
  void serializesRequiredFinanceCuSummaryFieldsWithExactNames() {
    QuoteCostRunSummaryResponse response = new QuoteCostRunSummaryResponse();
    response.setFinanceCuPricePerTon(new BigDecimal("90000"));
    response.setOaCuPricePerTon(new BigDecimal("102039"));
    response.setFinanceMaterialCost(new BigDecimal("261.128"));
    response.setOaMaterialCost(new BigDecimal("285.206"));
    response.setCuMaterialAdjustment(new BigDecimal("24.078"));
    response.setFinanceBaseTotalCost(new BigDecimal("1000"));
    response.setFinalQuoteAmount(new BigDecimal("1024.078"));
    response.setOaPricePrepareNo("PPR-OA-1");
    response.setFinancePricePrepareNo("PPR-FIN-1");

    var json = new ObjectMapper().valueToTree(response);

    assertThat(json.path("financeCuPricePerTon").decimalValue()).isEqualByComparingTo("90000");
    assertThat(json.path("oaCuPricePerTon").decimalValue()).isEqualByComparingTo("102039");
    assertThat(json.path("financeMaterialCost").decimalValue()).isEqualByComparingTo("261.128");
    assertThat(json.path("oaMaterialCost").decimalValue()).isEqualByComparingTo("285.206");
    assertThat(json.path("cuMaterialAdjustment").decimalValue()).isEqualByComparingTo("24.078");
    assertThat(json.path("financeBaseTotalCost").decimalValue()).isEqualByComparingTo("1000");
    assertThat(json.path("finalQuoteAmount").decimalValue()).isEqualByComparingTo("1024.078");
    assertThat(json.path("oaPricePrepareNo").asText()).isEqualTo("PPR-OA-1");
    assertThat(json.path("financePricePrepareNo").asText()).isEqualTo("PPR-FIN-1");
  }
}
