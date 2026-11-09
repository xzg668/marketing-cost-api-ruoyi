package com.sanhua.marketingcost.service.jjb;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.jjb.JjbReadModel;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class JjbPricePresentationTest {
  private final ObjectMapper json = new ObjectMapper();
  @ParameterizedTest
  @CsvSource({"PURCHASE_FIXED,固定价", "LINKED,联动价", "LINKED_LEGACY,联动价",
      "RANGE_FACTOR,区间价", "RANGE_LEGACY_FACTOR,区间价", "RANGE_QTY,区间价",
      "ODS_TIER,阶梯价", "INQUIRY,询价", "HOUSEHOLD_INQUIRY,询价（家用部件）",
      "INVOICE,发票价格", "ODS_ORDER,订单价", "TECH_FIXED,固定价（技术补录）"})
  void savedMonthlyEvidenceShowsItsOriginalSourceAndExactQuantityUnit(String kind, String expected) throws Exception {
    var row = JjbReadModel.Row.builder().key("PART:1").code("M").kind("PART")
        .quantity("0.00001").unitPrice("1259.115000").amount("0.012591").priceSource("月度物料价")
        .sources(List.of(new JjbReadModel.Source("lp_price_prepare_item", 7L, "已保存"))).build();
    var prepared = preparation(kind, "千克", "M", 1);
    var result = new JjbPricePresentation(json).apply(List.of(row), null, List.of(prepared)).getFirst();
    assertThat(result.priceSource()).isEqualTo(expected);
    assertThat(result.unit()).isEqualTo("千克");
    assertThat(result.quantity()).isEqualTo("0.00001");
    assertThat(result.amount()).isEqualTo(row.amount());
    assertThat(result.sources()).isEqualTo(row.sources());
  }

  @Test void anotherMaterialOrChildPriceConversionCannotSupplyAParentUnit() throws Exception {
    var row = JjbReadModel.Row.builder().key("PART:1").code("M").kind("PART")
        .priceSource("自制件价格生成").sources(List.of(new JjbReadModel.Source("lp_price_prepare_item",7L,"依据"))).build();
    for (var prepared : List.of(preparation("PURCHASE_FIXED","KG","CHILD",1),preparation("PURCHASE_FIXED","KG","M",1000))) {
      var result = new JjbPricePresentation(json).apply(List.of(row), null, List.of(prepared)).getFirst();
      assertThat(result.unit()).isNull();
      assertThat(result.priceSource()).isEqualTo("自制件价格生成");
    }
  }

  private PricePrepareItem preparation(String kind, String unit, String code, int multiplier) throws Exception {
    var prepared = new PricePrepareItem();prepared.setId(7L);prepared.setMaterialCode(code);
    prepared.setResultRefType("MONTHLY_PRICE");prepared.setMonthlyPriceId(9L);
    String snapshot = json.writeValueAsString(Map.of("request",Map.of("family",Map.of("materialCode",code,"unit",unit)),
        "unitPriceMultiplier",multiplier,"selection",Map.of("fact",Map.of("evidence",Map.of("json",json.writeValueAsString(Map.of("sourceKind",kind)))))));
    prepared.setInputSnapshotJson(new com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec(json).wrap(snapshot));
    return prepared;
  }
}
