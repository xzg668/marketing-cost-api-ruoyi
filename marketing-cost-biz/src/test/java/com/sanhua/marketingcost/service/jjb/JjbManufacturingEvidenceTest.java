package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.service.costing.MonthlyCostVersionService.Stored;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JjbManufacturingEvidenceTest {
  @Test void sourceLabelsUseOnlySavedWeightBasis() throws Exception {
    var json = new ObjectMapper();
    for (String source : List.of("SRM联动价表", "SRM区间价表")) {
      var row = json.createObjectNode().put("itemProcessType", "毛坯加工")
          .put("grossWeightG", "298.14").put("remark", "毛重来源=" + source + "；毛重取本次采用报价净重");
      assertThat(JjbManufacturingEvidence.describe(row).get("grossWeightSource")).isEqualTo(source);
    }
    assertThat(JjbManufacturingEvidence.grossWeightSource("毛坯加工",
        "毛重按本件净重加U9副产品废料重反算")).isEqualTo("U9理论净重＋副产品重量反算");
    assertThat(JjbManufacturingEvidence.grossWeightSource("毛坯加工", "历史没有记录")).isNull();
  }

  @Test void savedBlankProcessingExplainsItsActualPriceWithoutChangingTheSavedAmount() {
    var version=new QuoteCostRunVersion();version.setId(32L);version.setProductCode("PRODUCT");
    var part=new CostRunPartItem();part.setId(674L);part.setPartCode("BASE");part.setPricePrepareItemId(3121L);
    part.setQty(BigDecimal.ONE);part.setUnitPrice(new BigDecimal("0.582814"));part.setAmount(new BigDecimal("0.582814"));
    var prepare=new PricePrepareItem();prepare.setId(3121L);prepare.setMaterialCode("BASE");prepare.setResultRefType("MAKE_PART_PRICE");
    prepare.setInputSnapshotJson("""
      {"calculatorVersion":"MAKE_PART_V1","materialCode":"BASE","rows":[{
        "parentMaterialNo":"BASE","childMaterialNo":"BLANK","childMaterialName":"基座毛坯",
        "itemProcessType":"毛坯加工","stockUnit":"只","grossWeightG":24.8,"netWeightG":14,
        "rawUnitPrice":0.66070796460177,"scrapCode":"SCRAP","scrapName":"废不锈钢",
        "scrapUnitPrice":7.21238938053097,"costPrice":0.58281416,"parentTotalCostPrice":0.58281416}]}
      """);
    var model=new JjbProjection(new ObjectMapper()).project(new Stored(version,null,List.of(part),List.of()),
        new OaForm(),new OaFormItem(),List.of(),List.of(prepare));
    var row=new ObjectMapper().valueToTree(model.rows().getFirst());
    assertThat(row.path("manufacturing").size()).isEqualTo(1);
    var line=row.path("manufacturing").get(0);
    assertThat(line.path("processType").asText()).isEqualTo("毛坯加工");
    assertThat(line.path("rawUnitPrice").asText()).isEqualTo("0.66070796460177");
    assertThat(line.path("scrapWeightKg").asText()).isEqualTo("0.0108");
    assertThat(line.path("substitution").asText()).contains("0.66070796460177", "24.8", "14", "1000", "7.21238938053097", "0.58281416");
    assertThat(row.path("amount").asText()).isEqualTo("0.582814");
  }
}
