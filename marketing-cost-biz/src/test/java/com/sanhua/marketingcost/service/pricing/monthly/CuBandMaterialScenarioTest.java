package com.sanhua.marketingcost.service.pricing.monthly;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.service.scrap.*;
import com.sanhua.marketingcost.entity.ScrapPrice;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CuBandMaterialScenarioTest {
  static final String BRASS="AVG|美国柜装黄铜", H65="AVG|H65黄铜边料";
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec=new MonthlyPriceDocumentCodec(json);
  final MonthlyPriceScenarios scenarios=MonthlyScenarioTestSupport.create(null);
  static BigDecimal d(String x){return x==null?null:new BigDecimal(x);}
  public static FactorPriceSnapshot financial() {
    var bands=new ArrayList<FactorPriceSnapshot.CuBand>();
    String[][] values={{"90000","52.6232791440377","54.8967035346336","20.8310629161179"},
      {"100000","58.4703101600419","60.996337260704","23.1456254623532"},
      {"110000","64.317341176046","67.0959709867744","25.4601880085885"},
      {"120000","70.1643721920502","73.1956047128448","27.7747505548238"}};
    for(var row:values) {
      var factors=new LinkedHashMap<String,FactorPriceSnapshot.FactorValue>();
      factors.put(PriceFactorContext.COPPER,value(d(row[0]).movePointLeft(3).toPlainString()));
      factors.put(PriceFactorContext.ZINC,value(row[3]));factors.put(BRASS,value(row[1]));factors.put(H65,value(row[2]));
      bands.add(new FactorPriceSnapshot.CuBand("CU_"+row[0],d(row[0]),factors));
    }
    return new FactorPriceSnapshot(new FactorPriceScope("COMMERCIAL","210","2026-11","CU_90000",FactorPriceScope.Condition.GENERAL),
      30L,1,"1109.xls","hash","content",bands.getFirst().factors(),bands.stream().map(b->b.schemeCode()).toList(),"finance",LocalDateTime.of(2026,11,9,0,0),null,bands);
  }
  static FactorPriceSnapshot.FactorValue value(String amount){return new FactorPriceSnapshot.FactorValue(1L,1L,"因素","因素",d(amount),"CNY/KG",true,List.of(new FactorPriceSnapshot.Source(1L,"原表","AD11",amount,"元/公斤",null,"0.0000","SCHEME_VALUE")));}
  PriceFactorContext context(){return new PriceFactorContext("PART",new PriceMonthSelection("2026-11","2026-11"),financial(),null);}
  @ParameterizedTest @CsvSource({"80000,CU_90000","90000,CU_90000","95000,CU_90000","99999.99,CU_90000","100000,CU_100000","105000,CU_100000","110000,CU_110000","115000,CU_110000","120000,CU_120000","125000,CU_120000"})
  void exactBoundariesUseActualCuAndFloorBandForTwoSupplementalFactors(String copper,String band) {
    var original=context();var quoted=original.withCustomerMaterials(CustomerMaterialPrices.copperAndZinc(d(copper),null));
    assertThat(quoted.customerBand().schemeCode()).isEqualTo(band);
    assertThat(quoted.requiredPrice(PriceFactorContext.COPPER,FactorPriceUnit.CNY_TON,true)).isEqualByComparingTo(copper);
    for(String factor:List.of(BRASS,H65)) {
      assertThat(quoted.requiredPrice(factor,FactorPriceUnit.CNY_KG,true)).isEqualByComparingTo(quoted.customerBand().factors().get(factor).price());
      assertThat(quoted.inputSource(factor)).isEqualTo("FINANCE_CU_BAND");
    }
    assertThat(quoted.requiredPrice(PriceFactorContext.ZINC,FactorPriceUnit.CNY_KG,true)).isEqualByComparingTo("20.8310629161179");
    assertThat(quoted.inputSource(PriceFactorContext.ZINC)).isEqualTo("FINANCE_FACTOR");
    assertThat(quoted.financial()).isSameAs(original.financial());assertThat(quoted.financialOnly()).isEqualTo(original);
  }
  @Test void explicitZincWinsAndMissingCuDoesNotInventACustomerBand() throws Exception {
    var quoted=context().withCustomerMaterials(CustomerMaterialPrices.copperAndZinc(d("125000"),d("24000")));
    assertThat(quoted.requiredPrice(PriceFactorContext.ZINC,FactorPriceUnit.CNY_KG,true)).isEqualByComparingTo("24");
    assertThat(quoted.inputSource(PriceFactorContext.ZINC)).isEqualTo("OA_LOCKED");
    var restored=json.readValue(json.writeValueAsString(quoted.evidenceForFactors(Set.of(BRASS,PriceFactorContext.ZINC))),PriceFactorContext.class);
    assertThat(restored.requiredPrice(BRASS,FactorPriceUnit.CNY_KG,true)).isEqualByComparingTo("70.1643721920502");
    assertThat(restored.customerBand().factors()).containsOnlyKeys(BRASS,PriceFactorContext.ZINC,PriceFactorContext.COPPER);
    assertThat(context().withCustomerMaterials(CustomerMaterialPrices.copperAndZinc(null,d("24000"))).customerBand()).isNull();
  }
  @Test void missingFactorInSelectedBandIsNotBorrowedFromBaseline() {
    var base=financial();var bands=new ArrayList<>(base.cuBands());var last=bands.removeLast();
    bands.add(new FactorPriceSnapshot.CuBand(last.schemeCode(),last.copperPerTon(),Map.of(PriceFactorContext.COPPER,last.factors().get(PriceFactorContext.COPPER))));
    var ctx=new PriceFactorContext("PART",context().months(),base.withCuBands(bands),null).withCustomerMaterials(CustomerMaterialPrices.copperAndZinc(d("125000"),null));
    assertThatThrownBy(()->ctx.requiredPrice(BRASS,FactorPriceUnit.CNY_KG,true)).hasMessageContaining(BRASS).hasMessageContaining("CU_120000");
  }
  @ParameterizedTest @CsvSource({"AVG|美国柜装黄铜,58.4703101600419","AVG|H65黄铜边料,60.996337260704"})
  void inquiryWithoutDirectCopperUsesCorrectedNewTableBandAndRecognizesItsDifference(String key,String expected) {
    var calculation=new LinkedHashMap<String,Object>();calculation.put("priceType","联动价");calculation.put("expression","x");
    calculation.put("inputs",Map.of("x",financial().required(key).price()));calculation.put("factors",Map.of("x",key));
    calculation.put("financeFactors",Map.of(key,financial().required(key)));calculation.put("financial",financial());
    var selected=new Selection(1L,coverage(),PriceFact.normal(financial().required(key).price(),evidence(Map.of("snapshotSchema",1,"sourceKind","INQUIRY","source",Map.of("selected",Map.of("price",financial().required(key).price(),"calculation",calculation)))),Set.of()));
    var prices=Map.of(request("PART","只"),selected);var before=selected.fact().evidence();
    var pair=scenarios.calculate(prices,d("105000"),Map.of()).get(1L);
    assertThat(pair.customerPrice()).isEqualByComparingTo(expected);assertThat(pair.customerEvidenceJson()).contains("CU_100000","customerFactorContexts");
    assertThat(scenarios.preview(prices,d("105000"),Map.of()).get(prices.keySet().iterator().next()).customerPrice()).isEqualByComparingTo(expected);
    assertThat(new MonthlyMetalExposure(codec).read(prices)).containsEntry(1L,true);
    assertThat(selected.fact().evidence()).isEqualTo(before);
  }
  @Test void oldInquiryEvidenceRestoresItsExactPublicationWithoutChangingCapturedP0() throws Exception {
    var reader=org.mockito.Mockito.mock(com.sanhua.marketingcost.service.financefactor.FinanceFactorPriceReader.class);
    org.mockito.Mockito.when(reader.readCaptured(30L,1,"COMMERCIAL","210","2026-11","CU_90000",FactorPriceScope.Condition.GENERAL)).thenReturn(financial());
    var projected=new MonthlyPriceScenarios(codec,new com.sanhua.marketingcost.service.impl.OriginalLinkedRuleCalculator(),
        new com.sanhua.marketingcost.service.impl.OriginalRangeRuleCalculator(),new com.sanhua.marketingcost.service.impl.OriginalRangeRuleSnapshot(json),
        null,null,null,null,reader);
    var calculation=new LinkedHashMap<String,Object>();calculation.put("priceType","联动价");calculation.put("expression","x");
    calculation.put("inputs",Map.of("x",financial().required(BRASS).price()));calculation.put("factors",Map.of("x",BRASS));
    calculation.put("financeFactors",Map.of(BRASS,financial().required(BRASS)));calculation.put("financeBatchId",30L);
    calculation.put("financeRevision",1);calculation.put("financeMonth","2026-11");calculation.put("financeScheme","CU_90000");
    var selected=new Selection(1L,coverage(),PriceFact.normal(financial().required(BRASS).price(),evidence(Map.of("snapshotSchema",1,"sourceKind","INQUIRY","source",Map.of("selected",Map.of("price",financial().required(BRASS).price(),"calculation",calculation)))),Set.of()));
    var original=selected.fact().evidence();var pair=projected.calculate(Map.of(request("PART","只"),selected),d("105000")).get(1L);
    assertThat(pair.financialPrice()).isEqualByComparingTo("52.6232791440377");
    assertThat(pair.customerPrice()).isEqualByComparingTo("58.4703101600419");
    assertThat(pair.customerEvidenceJson()).contains("CU_100000");assertThat(selected.fact().evidence()).isEqualTo(original);
    org.mockito.Mockito.verify(reader).readCaptured(30L,1,"COMMERCIAL","210","2026-11","CU_90000",FactorPriceScope.Condition.GENERAL);
    org.mockito.Mockito.verifyNoMoreInteractions(reader);
  }
  @Test void scrapBrassOnlyFormulaChangesWithCopperAndKeepsItsOriginalP0() {
    var row=new ScrapPrice();row.setId(16L);row.setBusinessUnitType("COMMERCIAL");row.setPriceMonth("2026-11");row.setMaterialCode("301990218");row.setMaterialName("C3771/C3604干铜沫");row.setUnit("KG");row.setPriceType("LINKED");row.setExpression("([Brass]*1.039)/1.13");row.setSourceFormula("($N$2*1.039)/1.13");row.setFactorBindingsJson("{\"Brass\":\"AVG|美国柜装黄铜\"}");row.setRuleRevision(1);
    var calculator=new ScrapPriceCalculator(new ScrapPriceRuleCodec(json));var input=calculator.prepare(row,financial());var p0=calculator.calculate(input);
    var source=new MonthlyScrapPriceSource.Snapshot(1,"SCRAP_PRICE","2026-11",new MonthlyScrapPriceSource.Source(row,List.of(new MonthlyScrapPriceSource.Calculation(row,input,p0)),List.of(),"SINGLE"),new MonthlyScrapPriceSource.Basis("KG","CNY",TaxBasis.EXCLUDED,"IDENTITY"));
    var price=new Selection(1L,coverage(),PriceFact.normal(p0,evidence(source),Set.of()));var prices=Map.of(request("301990218","KG"),price);
    var pair=scenarios.calculate(prices,d("125000"),Map.of()).get(1L);
    assertThat(pair.financialPrice()).isEqualByComparingTo("48.385475248367407345");assertThat(pair.customerPrice()).isEqualByComparingTo("64.513966997823148496");
    assertThat(new MonthlyMetalExposure(codec).read(prices)).containsEntry(1L,true);
    assertThat(pair.customerPrice().subtract(pair.financialPrice()).multiply(d("-.0105"))).isNegative();
  }
  MonthlyPriceRequest request(String code,String unit){return new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL",code,null,unit,"CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);}
  MonthlyPriceCoverage coverage(){return new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any());}
  SourceEvidence evidence(Object data){return SourceEvidence.ofJson(codec.unwrap(codec.encode(data)));}
}
