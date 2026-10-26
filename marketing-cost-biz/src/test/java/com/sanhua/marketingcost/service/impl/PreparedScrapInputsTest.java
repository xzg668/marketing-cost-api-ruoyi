package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.SourceEvidence;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Tests the real DerivedResolver with captured mapping and monthly-child inputs, without source I/O. */
class PreparedScrapInputsTest {
  final PreparedFormulaInputsTest fixture=new PreparedFormulaInputsTest();
  static MaterialScrapRef reference(String code,String ratio){
    var row=new MaterialScrapRef();row.setId(31L);row.setBusinessUnitType("COMMERCIAL");row.setMaterialCode("RAW-001");row.setScrapCode(code);row.setRatio(ratio==null?null:new BigDecimal(ratio));row.setSourceDocNo("captured-ref-original");row.setEffectiveFrom(LocalDate.of(2026,10,1));return row;
  }
  static PreparedFormulaInputs.ScrapPricePerKgExclTax price(String amount){
    return new PreparedFormulaInputs.ScrapPricePerKgExclTax(new BigDecimal(amount),SourceEvidence.ofJson("{\"sourceKind\":\"TEST_CAPTURED_MONTHLY_CHILD\",\"monthlyPriceId\":17,\"unit\":\"KG\",\"currency\":\"CNY\",\"taxBasis\":\"EXCLUDED\"}"));
  }
  PriceLinkedFormulaPreviewResponse calculate(PreparedFormulaInputs.Scrap scrap,String taxMode,boolean vat,Integer taxIncluded){
    var definitions=new ArrayList<PriceVariable>();var variable=PreparedFormulaInputsTest.variable("scrap_price","废料价","DERIVED","{\"strategy\":\"SCRAP_REF\"}");variable.setTaxMode(taxMode);definitions.add(variable);if(vat)definitions.add(PreparedFormulaInputsTest.variable("vat_rate","税率","CONST","{\"value\":\"0.13\"}"));var inputs=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(definitions,List.of(),List.of()),PreparedFormulaInputsTest.capturedFinancial(),scrap);var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("废料价");rule.setTaxIncluded(taxIncluded);var result=fixture.service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),inputs);verifyNoInteractions(fixture.variables,fixture.rules,fixture.bindings,fixture.placeholders,fixture.identities,fixture.prices,fixture.finance,fixture.scrapReferences,fixture.scrapPrices);return result;
  }
  @Test void capturedMonthlyScrapKeepsMappingRatioAndOriginalVatOrder(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01","0.5"))),Map.of("SCRAP-01",price("10")));var result=calculate(scrap,"INCL",true,0);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("5");assertThat(result.getInputSnapshotJson()).contains("captured-ref-original","usedScrapReferences","TEST_CAPTURED_MONTHLY_CHILD","monthlyPriceId","1.13","0.5");
  }
  @Test void explicitExclTaxScrapNeedsNoVatVariable(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01","0.5"))),Map.of("SCRAP-01",price("10")));var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("5");
  }
  @Test void absentCapturedChildNeverBecomesZeroEvenWhenTheMappingRatioIsZero(){
    for(String ratio:List.of("0.5","0")){var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01",ratio))),Map.of());var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).isNotBlank();assertThat(result.getResult()).isNull();assertThat(result.getInputSnapshotJson()).isNull();}
  }
  @Test void anExplicitZeroChildIsARealPrice(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01","0.5"))),Map.of("SCRAP-01",price("0")));var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).isNull();assertThat(result.getResult()).isZero();assertThat(result.getInputSnapshotJson()).contains("TEST_CAPTURED_MONTHLY_CHILD");
  }
  @Test void conflictingCapturedMappingsAreRejectedBeforeSelectingAnyChild(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01","0.5"),reference("SCRAP-02","0.5"))),Map.of("SCRAP-01",price("10"),"SCRAP-02",price("20")));var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).contains("多个有效映射");assertThat(result.getResult()).isNull();
  }
  @Test void equalDuplicateMappingsAndMissingRatioKeepTheExistingDefault(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01",null),reference("SCRAP-01","1.00"))),Map.of("SCRAP-01",price("10")));var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("10");
  }
  @Test void changingOriginalAndReturnedMappingsCannotChangeCapturedScrapInputs(){
    var original=reference("SCRAP-01","0.5");var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(original)),Map.of("SCRAP-01",price("10")));original.setRatio(new BigDecimal("0.9"));scrap.references().get("RAW-001").getFirst().setScrapCode("CHANGED");var result=calculate(scrap,"EXCL",false,1);assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("5");assertThat(result.getInputSnapshotJson()).doesNotContain("CHANGED");
  }
  @Test void missingVatAndUnknownTaxModeCannotReturnAnAmount(){
    var scrap=new PreparedFormulaInputs.Scrap(Map.of("RAW-001",List.of(reference("SCRAP-01","0.5"))),Map.of("SCRAP-01",price("10")));var noVat=calculate(scrap,"INCL",false,1);assertThat(noVat.getError()).contains("vat_rate");assertThat(noVat.getResult()).isNull();var unknown=calculate(scrap,null,true,1);assertThat(unknown.getError()).contains("税价口径");assertThat(unknown.getResult()).isNull();
  }
}
