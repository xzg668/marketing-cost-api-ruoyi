package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class JjbSourceEvidenceTest {
  @Test void explainsTheCapturedApprovalDateRuleWithoutChangingHistoricalDateFields() throws Exception {
    var result=JjbSourceEvidence.describe(new ObjectMapper().readTree("""
        {"sourceKind":"ODS_ORDER","source":{"selectionRule":"RATIO_MAX","ratios":[],
        "orderSelection":{"rule":"LATEST_SOURCE_APPROVAL","documentNumber":"DDJGCG05202604150004","documentDate":"2026-04-15"},
        "selected":{"source":"订单价格审批单-DDJGCG05202604150004","price":1094.6903,"effDate":null,"expDate":null}}}
        """));
    assertThat(result.get("selectionReason")).contains("供货比率","审批日期取最新，同日取最高单价","2026-04-15").doesNotContain("流水");
    assertThat(result).doesNotContainKeys("effectiveFrom","effectiveTo");
  }
  @Test void orderEvidenceRetainsApprovalOrderLineAndPriceWithoutInventingDates() throws Exception {
    var result=JjbSourceEvidence.describe(new ObjectMapper().readTree("""
        {"sourceKind":"ODS_ORDER","source":{"selectionRule":"SINGLE","ratios":[],"selected":{
        "source":"订单价格审批单-DDJGCG05202603230003","orderNo":"CGDDCG05202603020033",
        "orderLineNo":"3","price":1259.115,"effDate":null,"expDate":null}}}
        """));
    assertThat(result).containsEntry("table","ods_sy_v_mm_material_order_price_cg05_df")
        .containsEntry("document","订单价格审批单-DDJGCG05202603230003")
        .containsEntry("orderNo","CGDDCG05202603020033").containsEntry("orderLineNo","3")
        .containsEntry("sourcePrice","1259.115").doesNotContainKeys("effectiveFrom","effectiveTo");
  }
  @Test void rawProcessingExplainsWeightsInGramsAndPricePerKilogram() throws Exception {
    var result=JjbManufacturingEvidence.describe(new ObjectMapper().readTree("""
        {"itemProcessType":"原材料加工","grossWeightG":20,"netWeightG":12,
         "rawUnitPrice":50,"scrapUnitPrice":10,"costPrice":0.92}
        """));
    assertThat(result).containsEntry("rawPriceUnit","元/kg").containsEntry("scrapWeightKg","0.008")
        .containsEntry("materialAmount","1").containsEntry("scrapDeduction","0.08");
    assertThat(result.get("substitution")).contains("20 ÷ 1000 × 50", "0.92");
  }
}
