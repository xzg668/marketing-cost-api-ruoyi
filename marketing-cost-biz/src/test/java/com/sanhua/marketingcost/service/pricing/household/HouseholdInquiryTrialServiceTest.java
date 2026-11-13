package com.sanhua.marketingcost.service.pricing.household;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.monthly.HouseholdInquiryPriceRow;
import com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class HouseholdInquiryTrialServiceTest {
  final HouseholdInquiryService inquiries=mock(HouseholdInquiryService.class);
  final HouseholdInquiryStorage storage=mock(HouseholdInquiryStorage.class);
  final FinanceFactorPriceReader finance=mock(FinanceFactorPriceReader.class);
  final HouseholdInquiryTrialService service=new HouseholdInquiryTrialService(inquiries,storage,finance);
  static BigDecimal d(String s) { return new BigDecimal(s); }

  @BeforeEach void setup() {
    source("208200575","57.4","0.8");
    when(finance.readDefault(any(),any())).thenReturn(base("2026-11","2026-11","90","20.8310629161179"));
  }
  void source(String code,String cu,String zn) {
    var refs=Map.of("F",new HouseholdInquiryWorkbookParser.ReferenceValue("铜重（克）","NUMERIC",cu,null,null),
        "G",new HouseholdInquiryWorkbookParser.ReferenceValue("锌重（克）","NUMERIC",zn,null,null),
        "T",new HouseholdInquiryWorkbookParser.ReferenceValue("Cu变量","NUMERIC","1",null,null),
        "U",new HouseholdInquiryWorkbookParser.ReferenceValue("Zn变量","NUMERIC","2",null,null));
    var original=new HouseholdInquiryWorkbookParser.SourceRow(List.of(3),code,"线圈","MODEL","18.9343","0.085","EXCEL_RATIO",List.of(),refs);
    var context=new HouseholdInquiryModels.PriceContext("件","CNY",TaxBasis.EXCLUDED);
    var row=new HouseholdInquiryModels.PriceView(7,code,"线圈","MODEL",1,true,"18.9343","0.085","2026-11",context,2,LocalDateTime.of(2026,11,7,0,0),List.of(3));
    when(inquiries.financial("COMMERCIAL",7)).thenReturn(new HouseholdInquiryModels.FinancialView(row,
        new HouseholdInquiryModels.StoredEvidence(original,context,"2026-11"),"original.xlsx","hash"));
    var stored=new HouseholdInquiryPriceRow();stored.setId(7L);
    when(storage.asOfKeys(eq("COMMERCIAL"),any(),any())).thenReturn(List.of(stored));
  }
  static PriceFactorContext base(String requested,String actual,String cu,String zn) {
    var factors=new HashMap<String,FactorPriceSnapshot.FactorValue>();
    Map.of(PriceFactorContext.COPPER,cu,PriceFactorContext.ZINC,zn).forEach((k,v)->factors.put(k,
        new FactorPriceSnapshot.FactorValue(1L,1L,k,k,d(v),"CNY/KG",true,List.of())));
    var snapshot=new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL","210",actual,"DEFAULT",FactorPriceScope.Condition.GENERAL),
        1L,1,"base.xlsx","hash","hash",factors,List.of("DEFAULT"),"test",LocalDateTime.of(2026,11,1,0,0),null);
    return new PriceFactorContext("208200575",new PriceMonthSelection(requested,actual),snapshot,null);
  }
  HouseholdInquiryTrialService.Request request(String cu,String zn) {
    return new HouseholdInquiryTrialService.Request("2026-11",cu==null?null:d(cu),zn==null?null:d(zn));
  }
  @Test void exactSharedCostingFormulaUsesFinancialAndCustomerPricesRatherThanOriginalVariables() {
    var result=service.trial("COMMERCIAL",7,request("120000","27774.7505548238"));
    assertThat(result.adjustedPrice()).isEqualTo("20.463109690363685593");
    assertThat(result.originalPrice()).isEqualTo("18.9343");
    assertThat(result.factors().getFirst().basePerTon()).isEqualTo("90000");
    assertThat(result.factors().getFirst().customerPerTon()).isEqualTo("120000");
    assertThat(result.factors().get(1).customerPerTon()).isEqualTo("27774.7505548238");
    verify(finance).readDefault(argThat(c->c.priceOrgCode().equals("210")&&c.priceMonth().equals("2026-11")),eq("208200575"));
  }
  @Test void blankAndPartialInputsRetainIndependentFinancialBaselinesAndNegativeDifferences() {
    var baseline=service.trial("COMMERCIAL",7,request(null,null));
    assertThat(baseline.adjustedPrice()).isEqualTo(baseline.originalPrice());
    assertThat(baseline.difference()).isEqualTo("0");
    var lower=service.trial("COMMERCIAL",7,request("80000",null));
    assertThat(lower.adjustedPrice()).isEqualTo("18.426335398230088496");
    assertThat(lower.factors().get(1).fromCustomer()).isFalse();
    var zinc=service.trial("COMMERCIAL",7,request(null,"28000"));
    assertThat(zinc.factors().getFirst().fromCustomer()).isFalse();
  }
  @Test void carriedInquiryUsesRequestedMonthsBaselineAndReportsActualFinanceMonth() {
    when(finance.readDefault(any(),any())).thenReturn(base("2026-12","2026-11","100","24"));
    var result=service.trial("COMMERCIAL",7,new HouseholdInquiryTrialService.Request("2026-12",d("120000"),d("28000")));
    assertThat(result.pricingMonth()).isEqualTo("2026-12");
    assertThat(result.source().sourceMonth()).isEqualTo("2026-11");
    assertThat(result.financeMonth()).isEqualTo("2026-11");
    assertThat(result.adjustedPrice()).isEqualTo("19.953061061946902655");
  }
  @Test void missingCodeUsesSpecificationIdentityAndZeroMetalNeedsNoFinance() {
    source(null,"0","0");
    var result=service.trial("COMMERCIAL",7,request("120000",null));
    assertThat(result.adjustedPrice()).isEqualTo("18.9343");
    assertThat(result.financeMonth()).isNull();
    verifyNoInteractions(finance);
    verify(storage).asOfKeys("COMMERCIAL",List.of(HouseholdInquiryKey.of(null,"MODEL")),YearMonth.of(2026,11));
  }
  @Test void invalidCustomerValuesMonthsAndSupersededSourcesFailWithoutMisleadingPrice() {
    for(String value:List.of("0","-1"))
      assertThatThrownBy(()->service.trial("COMMERCIAL",7,request(value,null))).hasMessageContaining("大于0");
    for(String month:List.of("2026-13","0000-01","2026-1",""))
      assertThatThrownBy(()->service.trial("COMMERCIAL",7,new HouseholdInquiryTrialService.Request(month,null,null))).hasMessageContaining("月份");
    when(storage.asOfKeys(any(),any(),any())).thenReturn(List.of());
    assertThatThrownBy(()->service.trial("COMMERCIAL",7,request(null,null))).hasMessageContaining("来源已变化");
  }
  @Test void missingFinanceAndWrongBusinessUnitAreNotTreatedAsZero() {
    when(finance.readDefault(any(),any())).thenThrow(new FinanceFactorPriceReader.MissingPublicationException("未发布商用基价"));
    assertThatThrownBy(()->service.trial("COMMERCIAL",7,request(null,null))).hasMessageContaining("未发布");
    when(inquiries.financial("HOUSEHOLD",7)).thenThrow(new IllegalArgumentException("来源不存在或无权访问"));
    assertThatThrownBy(()->service.trial("HOUSEHOLD",7,request(null,null))).hasMessageContaining("无权");
    assertThat(HouseholdInquiryTrialService.class.getAnnotation(org.springframework.transaction.annotation.Transactional.class).readOnly()).isTrue();
  }
  @Test void apiDecimalsAreTextAndTrialDoesNotChangeSourceOrCallAnyWritePort() {
    var before=inquiries.financial("COMMERCIAL",7);
    var result=service.trial("COMMERCIAL",7,request("120000","27774.7505548238"));
    assertThat(new ObjectMapper().findAndRegisterModules().valueToTree(result).path("adjustedPrice").isTextual()).isTrue();
    assertThat(inquiries.financial("COMMERCIAL",7)).isSameAs(before);
    verify(storage).asOfKeys(any(),any(),any());verifyNoMoreInteractions(storage);
  }
}
