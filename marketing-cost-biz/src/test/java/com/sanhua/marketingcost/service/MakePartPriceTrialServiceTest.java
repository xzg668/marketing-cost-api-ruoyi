package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.MakePartPriceCalcRowMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import com.sanhua.marketingcost.service.impl.*;
import com.sanhua.marketingcost.service.pricing.*;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import com.sanhua.marketingcost.formula.workbook.WorkbookFormulaCompiler.*;
import java.math.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MakePartPriceTrialServiceTest {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(json);
  final MakePartPriceCalcRowMapper mapper = mock(MakePartPriceCalcRowMapper.class);
  final MakePartPriceTrialService service = new MakePartPriceTrialService(mapper, codec,
      MonthlyScenarioTestSupport.create(null), new MakePartPriceTraceSource(codec));
  MakePartPriceCalcRow saved;
  @BeforeEach void setup() {
    authenticate("COMMERCIAL");
    saved = row(1L);
    when(mapper.selectById(1L)).thenAnswer(call -> saved);
    when(mapper.selectList(any())).thenAnswer(call -> List.of(saved));
  }
  @AfterEach void clear() { SecurityContextHolder.clearContext(); }
  static void authenticate(String unit) {
    var auth = new UsernamePasswordAuthenticationToken("test", "unused");
    auth.setDetails(Map.of(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, unit));
    SecurityContextHolder.getContext().setAuthentication(auth);
  }
  @Test void copperRepricesBothInputsAndKeepsSavedRowsUnchanged() {
    var before = codec.encode(saved);
    var base = service.calculate(1,null);
    var result = service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("120000")));
    assertThat(base.originalPrice()).isEqualTo("1.86645971");
    assertThat(result.trialPrice()).isEqualTo("2.45847024");
    assertThat(result.difference()).isEqualTo("0.59201053");
    assertThat(result.lines().getFirst().raw().factors().getFirst().trial()).isEqualTo("120");
    assertThat(result.lines().getFirst().raw().formula()).contains("Cu（铜价）").doesNotContain("factor_0");
    assertThat(result.lines().getFirst().raw().originalCalculation()).contains("90");
    assertThat(result.lines().getFirst().scrap().copperLinked()).isTrue();
    assertThat(codec.encode(saved)).isEqualTo(before);
    verify(mapper,never()).updateById(any(MakePartPriceCalcRow.class)); verify(mapper,never()).insert(any(MakePartPriceCalcRow.class));
  }
  @Test void lowerAndUnchangedCopperPreserveNegativeAndZeroDifferences() {
    var base=service.calculate(1,null);
    assertThat(new BigDecimal(service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("80000"))).difference())).isNegative();
    assertThat(service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("90000"))).difference()).isEqualTo("0");
  }
  @Test void oneParentIncludesAllOfItsInputsInTheTotal() {
    var sibling=row(2L);
    saved.setParentTotalCostPrice(d("3.73291942")); sibling.setParentTotalCostPrice(d("3.73291942"));
    when(mapper.selectList(any())).thenReturn(List.of(saved,sibling));
    var base=service.calculate(1,null);
    var trial=service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("120000")));
    assertThat(trial.lines()).hasSize(2); assertThat(trial.trialPrice()).isEqualTo("4.91694048");
    assertThat(trial.difference()).isEqualTo("1.18402106");
  }
  @Test void changedEvidenceAndInvalidCopperAreRejected() {
    var base=service.calculate(1,null);
    for (var value:List.of("0","-1","1000000001"))
      assertThatThrownBy(()->service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d(value)))).hasMessageContaining("Cu价格");
    saved.setGrossWeightG(d("24"));
    assertThatThrownBy(()->service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("100000")))).hasMessageContaining("已更新");
  }
  @Test void mismatchedSavedCostCannotBePresentedAsSuccessfulTrial() {
    saved.setCostPrice(d("999"));
    var result=service.calculate(1,null);
    assertThat(result.trialAvailable()).isFalse(); assertThat(result.message()).contains("原价与保存");
  }
  @Test void businessUnitCannotBeOverriddenEvenForAnExistingId() {
    authenticate("HOUSEHOLD");
    assertThatThrownBy(()->service.calculate(1,null)).hasMessageContaining("业务单元");
    verify(mapper,never()).selectList(any());
  }
  @Test void fixedBlankAndConfirmedNoScrapRemainUnchanged() {
    saved.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_BLANK);
    saved.setRawInputSnapshotJson(fixed("RAW",10L,"2")); saved.setRawUnitPrice(d("2"));
    saved.setNoScrapConfirmed(true); saved.setScrapInputSnapshotJson(null); saved.setScrapUnitPrice(BigDecimal.ZERO);
    saved=new MakePartPriceCalculator().calculate(List.of(saved)).getFirst();
    var base=service.calculate(1,null);var result=service.calculate(1,new MakePartPriceTrialService.Request(base.basis(),d("120000")));
    assertThat(result.trialPrice()).isEqualTo("2"); assertThat(result.lines().getFirst().rawQuantity()).isEqualTo("1");
    assertThat(result.lines().getFirst().scrapWeightG()).isEqualTo("0"); assertThat(result.lines().getFirst().raw().copperLinked()).isFalse();
  }
  @Test void nonWeightInputUsesQuantityAndSavedActualRecovery() {
    saved.setStockUnit("米"); saved.setQtyPerParent(d(".0108"));
    saved.setRecoveredScrapWeightG(d(".0317")); saved.setRawInputSnapshotJson(fixed("RAW",10L,".785")); saved.setRawUnitPrice(d(".785"));
    saved=new MakePartPriceCalculator().calculate(List.of(saved)).getFirst();
    var result=service.calculate(1,null);
    assertThat(result.lines().getFirst().rawQuantityUnit()).isEqualTo("米");
    assertThat(result.lines().getFirst().rawQuantity()).isEqualTo("0.0108");
    assertThat(result.lines().getFirst().scrapWeightG()).isEqualTo("0.0317");
  }
  MakePartPriceCalcRow row(long id) {
    var row=new MakePartPriceCalcRow();row.setId(id);row.setBusinessUnitType("COMMERCIAL");row.setPricingMonth("2026-11");
    row.setCalcBatchId("test");row.setSourceCostingRowId(10L);row.setParentMaterialNo("MAKE");row.setChildMaterialNo("RAW");row.setScrapCode("SCRAP");
    row.setPriceScenarioType("FINANCE_QUOTE_BASE");row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);
    row.setStockUnit("kg");row.setQtyPerParent(d(".02349052"));row.setGrossWeightG(d("23.49052"));row.setNetWeightG(d("22.25585"));
    row.setRawInputSnapshotJson(linked("RAW",10L,"ROUND([factor_0]+4.350001,4)/1.13"));
    row.setScrapInputSnapshotJson(linked("SCRAP",11L,"[factor_0]*.965/1.13"));
    row.setStatus("OK");
    return new MakePartFinancialPrices(codec).project(List.of(row)).getFirst();
  }
  String linked(String code,long id,String expression) {
    var scope=new FactorPriceScope("COMMERCIAL","210","2026-11","CU_90000",FactorPriceScope.Condition.GENERAL);
    var copper=new FactorPriceSnapshot.FactorValue(1L,1L,"电解铜","Cu",d("90"),"CNY/KG",true,List.of());
    var financial=new FactorPriceSnapshot(scope,1L,1,"财务基价.xlsx","hash","hash",Map.of("AVG|1#CU",copper),List.of("CU_90000"),"finance",LocalDateTime.of(2026,11,1,0,0),null);
    var context=new PriceFactorContext(code,new PriceMonthSelection("2026-11","2026-11"),financial,null);
    var key=new MaterialPriceRuleKey("COMMERCIAL","210",code,"S1","供应商","SPEC","kg");
    var factor=new OriginalLinkedWorkbookParser.FactorInput("AVG|1#CU",new Address("基价","B1"),FactorPriceUnit.CNY_KG,"公斤","AVG|1#CU","SAME_CANONICAL_FACTOR");
    var rule=new OriginalLinkedWorkbookParser.Rule(key,code,"原稿",1,"B1",expression,expression,"0.0000",false,new CompiledFormula(expression,List.of(),Map.of()),Map.of("factor_0",factor),BigDecimal.ZERO);
    var calculated=new OriginalLinkedRuleCalculator().calculate(rule,context);
    var pub=new FinanceEffectivePrice();pub.setCalculationJson(codec.unwrap(codec.encode(new FinanceEffectivePriceService.Calculation(context,calculated,null,rule))));
    var source=Map.of("selectedPublication",pub,"calculationJson",codec.unwrap(codec.encode(Map.of("context",context))));
    return snapshot(code,id,calculated.priceExcludingTax().setScale(18,RoundingMode.HALF_UP),"LINKED",source);
  }
  String fixed(String code,long id,String price) {return snapshot(code,id,d(price),"PURCHASE_FIXED",Map.of("selected",Map.of("fixedPrice",price)));}
  String snapshot(String code,long id,BigDecimal price,String kind,Object source) {
    var request=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.of(2026,11),"COMMERCIAL",code,null,"kg","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    var fact=PriceFact.normal(price,SourceEvidence.ofJson(codec.unwrap(codec.encode(Map.of("snapshotSchema",1,"sourceKind",kind,"source",source)))),Set.of());
    var selection=new Selection(id,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),fact);
    return codec.encode(new MonthlyPreparationPrice(1,"MONTHLY_PRICE",request,selection,price,price,BigDecimal.ONE,null,null,null));
  }
  static BigDecimal d(String value) {return new BigDecimal(value);}
}
