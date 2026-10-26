package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MonthlyContextDocumentTest {
  @ParameterizedTest @ValueSource(booleans={false,true})
  void persistedContextRestoresWithAndWithoutApplicationJacksonModules(boolean applicationModules) {
    var mapper=new ObjectMapper();if(applicationModules)mapper.findAndRegisterModules();
    var codec=new MonthlyPriceDocumentCodec(mapper);
    var original=new MonthlyMaterialPrices.Context("COMMERCIAL","OA-JSON",10L,"TOP",YearMonth.of(2026,10),LocalDateTime.of(2026,10,23,9,0),"fixture","210");
    String saved=codec.encode(original);assertThat(saved).doesNotContain("supplierReviews","successfulReviews","ReviewEvidenceKey");
    var restored=codec.decode(saved,MonthlyMaterialPrices.Context.class);
    assertThat(restored.oaNo()).isEqualTo(original.oaNo());assertThat(restored.oaFormItemId()).isEqualTo(original.oaFormItemId());
    assertThat(restored.month()).isEqualTo(original.month());assertThat(restored.priceAsOfTime()).isEqualTo(original.priceAsOfTime());
    assertThat(restored.supplierReviews()).isNotSameAs(original.supplierReviews());assertThat(restored.supplierReviews().oaFormItemId()).isEqualTo(10L);assertThat(restored.supplierReviews().priceOrgCode()).isEqualTo("210");
  }
}
