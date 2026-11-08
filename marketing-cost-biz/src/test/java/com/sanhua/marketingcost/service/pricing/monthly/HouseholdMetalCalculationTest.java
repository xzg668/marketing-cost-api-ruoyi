package com.sanhua.marketingcost.service.pricing.monthly;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class HouseholdMetalCalculationTest {
  static BigDecimal d(String value){return new BigDecimal(value);}
  static PriceFactorContext finance(String month,String copper,String zinc) {
    var factors=new LinkedHashMap<String,FactorPriceSnapshot.FactorValue>();
    Map.of(PriceFactorContext.COPPER,copper,PriceFactorContext.ZINC,zinc).forEach((key,value)->factors.put(key,new FactorPriceSnapshot.FactorValue(1L,1L,key,key,d(value),"CNY/KG",true,List.of())));
    var snapshot=new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL","210",month,"DEFAULT",FactorPriceScope.Condition.GENERAL),1L,1,"base.xlsx","hash","hash",factors,List.of("DEFAULT"),"test",java.time.LocalDateTime.of(2026,11,1,0,0),null);
    return new PriceFactorContext("208200575",new PriceMonthSelection(month,month),snapshot,null);
  }
  static HouseholdMetalCalculation.Inputs inputs(){return new HouseholdMetalCalculation.Inputs(d("57.4"),d("0.8"),finance("2026-11","90","20.8310629161179"));}
  @Test void actualWorkbookCustomerValuesReproduceWAndKeepEveryInput() throws Exception {
    var result=HouseholdMetalCalculation.calculate(d("18.9343"),inputs(),CustomerMaterialPrices.copperAndZinc(d("120000"),d("27774.7505548238")));
    assertThat(result.calculatedW()).isEqualByComparingTo("20.463109690363685593");
    assertThat(result.originalPrice()).isEqualByComparingTo("18.9343");
    assertThat(result.customerCopper()).isEqualByComparingTo("120");assertThat(result.customerZinc()).isEqualByComparingTo("27.7747505548238");
    assertThat(result.copperFromCustomer()).isTrue();assertThat(result.zincFromCustomer()).isTrue();
    var json=new ObjectMapper().findAndRegisterModules();
    assertThat(json.readValue(json.writeValueAsString(result),HouseholdMetalCalculation.Trace.class)).isEqualTo(result);
  }
  @Test void omittedAndMatchingValuesHaveNoDifference() {
    for(var customer:List.of(CustomerMaterialPrices.copperAndZinc(null,null),CustomerMaterialPrices.copperAndZinc(d("90000"),d("20831.0629161179"))))
      assertThat(HouseholdMetalCalculation.calculate(d("18.9343"),inputs(),customer).calculatedW()).isEqualByComparingTo("18.9343");
    var missing=HouseholdMetalCalculation.calculate(d("18.9343"),inputs(),CustomerMaterialPrices.copperAndZinc(null,null));
    assertThat(missing.copperFromCustomer()).isFalse();assertThat(missing.zincFromCustomer()).isFalse();
  }
  @Test void eachMissingMetalFallsBackIndependentlyAndNegativeDifferencesSurvive() {
    var cu=HouseholdMetalCalculation.calculate(d("18.9343"),inputs(),CustomerMaterialPrices.copperAndZinc(d("80000"),null));
    assertThat(cu.calculatedW()).isEqualByComparingTo("18.426335398230088496");assertThat(cu.zincDifference()).isZero();
    var zn=HouseholdMetalCalculation.calculate(d("18.9343"),inputs(),CustomerMaterialPrices.copperAndZinc(null,d("20000")));
    assertThat(zn.copperDifference()).isZero();assertThat(zn.zincDifference()).isNegative();
  }
  @Test void decemberBaselineChangesOnlyCustomerDifferenceAndNeverOriginalInquiry() {
    var input=new HouseholdMetalCalculation.Inputs(d("57.4"),d("0.8"),finance("2026-12","100","24"));
    var result=HouseholdMetalCalculation.calculate(d("18.9343"),input,CustomerMaterialPrices.copperAndZinc(d("120000"),d("28000")));
    assertThat(result.calculatedW()).isEqualByComparingTo("19.953061061946902655");assertThat(result.originalPrice()).isEqualByComparingTo("18.9343");
    assertThat(result.pricingMonth()).isEqualTo("2026-12");
  }
  @Test void zeroWeightsDoNotRequireFinancialPublicationAndCannotCreateMetalDifference() {
    var input=new HouseholdMetalCalculation.Inputs(BigDecimal.ZERO,BigDecimal.ZERO,null);
    assertThat(HouseholdMetalCalculation.calculate(d("10"),input,CustomerMaterialPrices.copperAndZinc(d("120000"),d("28000"))).calculatedW()).isEqualByComparingTo("10");
    assertThatThrownBy(()->new HouseholdMetalCalculation.Inputs(d("1"),BigDecimal.ZERO,null)).hasMessageContaining("财务基价");
  }
}
