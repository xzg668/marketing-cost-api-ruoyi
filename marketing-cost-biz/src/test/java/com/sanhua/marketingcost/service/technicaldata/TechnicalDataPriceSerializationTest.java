package com.sanhua.marketingcost.service.technicaldata;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.technicaldata.*;
import com.sanhua.marketingcost.dto.technicaldata.TechnicalDataSupplementContent.*;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class TechnicalDataPriceSerializationTest {
  @Test void referenceSearchPreservesParametersWithoutChangingSnapshotFormat() throws Exception {
    var amount = new BigDecimal("123456789012.123456789012");
    var source = new TechnicalDataPriceReference(1L,"CODE",null,null,"只","210","COMMERCIAL",
        "2026-09","[process_fee]",null,new PriceParameters(amount,null,"g",amount,null,"元/只"),
        0,null,null,"fingerprint",List.of());
    var mapper = new ObjectMapper();
    var view = mapper.readTree(mapper.writeValueAsString(TechnicalDataPriceReferenceView.from(source)));
    assertThat(view.path("parameters").path("processFee").textValue()).isEqualTo(amount.toPlainString());
    assertThat(view.path("parameters").path("blankWeight").textValue()).isEqualTo(amount.toPlainString());
    assertThat(mapper.readTree(mapper.writeValueAsString(source)).path("parameters").path("processFee").isNumber()).isTrue();
  }

  @Test void browserReceivesExactDecimalTextWithoutChangingFrozenContentSerialization() throws Exception {
    var price = new BigDecimal("123456789012.123456789012");
    var content = new Prices(List.of(new PriceItem("key","CODE","210","只","CNY","FIXED",price,null,null,null,null,null,null)));
    var response = new TechnicalDataPriceResponse(1L,2L,0,3L,"DRAFT","READY",true,false,content,null,List.of(),List.of(),List.of());
    var mapper = new ObjectMapper();
    var web = mapper.readTree(mapper.writeValueAsString(response)).path("content").path("items").get(0).path("unitPrice");
    assertThat(web.isTextual()).isTrue(); assertThat(web.textValue()).isEqualTo(price.toPlainString());
    assertThat(mapper.readTree(mapper.writeValueAsString(content)).path("items").get(0).path("unitPrice").isNumber()).isTrue();
  }
}
