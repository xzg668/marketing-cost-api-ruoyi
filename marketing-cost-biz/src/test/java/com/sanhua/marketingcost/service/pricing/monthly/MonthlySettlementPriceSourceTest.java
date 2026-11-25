package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.settlement.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class MonthlySettlementPriceSourceTest {
  final SettlementPriceReader reader=mock(SettlementPriceReader.class);
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final MonthlySettlementPriceSource source=new MonthlySettlementPriceSource(reader,new MonthlyPriceDocumentCodec(json),json);
  final MonthlyMaterialPrices.Context context=new MonthlyMaterialPrices.Context("COMMERCIAL","INQ",1L,"TOP",YearMonth.of(2026,11),LocalDateTime.of(2026,11,24,0,0),"test","210");
  MonthlyPriceRequest request(String code) {
    return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(context.month(),"COMMERCIAL",code,"MODEL","件","CNY",TaxBasis.EXCLUDED,null,code.isEmpty()),"210",NonCuConditions.empty(),BigDecimal.ONE);
  }
  @Test void modelOnlyInquiryCanContinuePastBothSettlementTypes() {
    var request=request("");
    for(var type:SettlementPriceType.values()) {
      var result=source.read(context,Map.of(request,type));
      assertThat(result.values()).isEmpty();
      assertThat(result.problems().get(request).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
    }
    verifyNoInteractions(reader);
  }
  @Test void ordinaryCodedRequestsInTheSameBatchStillReadSettlement() {
    var model=request("");var coded=request("ACTUAL-CODE");
    when(reader.resolveBatch(anyList())).thenReturn(Map.of());
    var result=source.read(context,Map.of(model,SettlementPriceType.SETTLE_FIXED,coded,SettlementPriceType.SETTLE_FIXED));
    assertThat(result.problems().get(model).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.MISSING);
    assertThat(result.problems().get(coded).reason()).isEqualTo(MonthlyAdoptionRetryPolicy.Failure.EVIDENCE_UNAVAILABLE);
    verify(reader).resolveBatch(List.of(new SettlementPriceRequest("COMMERCIAL","ACTUAL-CODE","2026-11","210",SettlementPriceType.SETTLE_FIXED)));
  }
}
