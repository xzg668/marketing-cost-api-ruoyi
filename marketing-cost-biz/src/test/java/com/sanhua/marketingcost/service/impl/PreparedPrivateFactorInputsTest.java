package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.formula.registry.PreparedFormulaInputs;
import com.sanhua.marketingcost.service.pricing.PriceFactorContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class PreparedPrivateFactorInputsTest {
  final PreparedFormulaInputsTest fixture=new PreparedFormulaInputsTest();
  static FactorMonthlyPrice price(long id,String month,String value){
    var row=new FactorMonthlyPrice();row.setId(id);row.setFactorIdentityId(44L);row.setPriceMonth(month);row.setPrice(value==null?null:new BigDecimal(value));row.setSchemeCode("LEGACY");row.setStatus("ACTIVE");row.setSourceUploadBatchId(204L);row.setUnit("CNY/KG");return row;
  }
  PriceLinkedFormulaPreviewResponse calculate(List<FactorMonthlyPrice> rows,Long snapshotId,boolean managed){
    var identity=new FactorIdentity();identity.setId(44L);identity.setBusinessUnitType("COMMERCIAL");identity.setStatus("ACTIVE");identity.setCanonicalFactorKey("AVG|PRIVATE_STEEL");
    String params="{\"factorIdentityId\":44"+(snapshotId==null?"":",\"factorMonthlyPriceId\":"+snapshotId)+"}";
    var variable=PreparedFormulaInputsTest.variable("private_price","私有钢价","FINANCE",params);
    var keys=managed?Set.of(PriceFactorContext.COPPER,"AVG|PRIVATE_STEEL"):Set.of(PriceFactorContext.COPPER);
    var inputs=new PreparedFormulaInputs(new PreparedFormulaInputs.Catalog(List.of(variable),List.of(),List.of()),new PreparedFormulaInputs.Financial(PreparedFormulaInputsTest.financial(),Map.of(44L,identity),keys,rows));
    var rule=PreparedFormulaInputsTest.rule();rule.setFormulaExpr("私有钢价");rule.setTaxIncluded(1);
    var result=fixture.service().previewPrepared(rule,"2026-10",LocalDate.of(2026,10,23),inputs);
    verifyNoInteractions(fixture.variables,fixture.rules,fixture.bindings,fixture.placeholders,fixture.identities,fixture.prices,fixture.finance,fixture.scrapReferences,fixture.scrapPrices);
    return result;
  }
  @Test void privateMonthlyFactorKeepsCapturedSqlHeadAndItsIndependentSavedRow(){
    var result=calculate(List.of(price(1001,"2026-10","11"),price(999,"2026-09","7")),null,false);
    assertThat(result.getError()).isNull();assertThat(result.getResult()).isEqualByComparingTo("11");assertThat(result.getInputSnapshotJson()).contains("usedPrivateFactors","1001","204","2026-10");
  }
  @Test void latestNullPrivateFactorCannotSilentlyFallBackToAnOlderAmount(){
    var result=calculate(List.of(price(1002,"2026-10",null),price(999,"2026-09","7")),null,false);assertThat(result.getError()).isNotBlank();assertThat(result.getResult()).isNull();assertThat(result.getInputSnapshotJson()).isNull();
  }
  @Test void explicitSnapshotFallbackRetainsTheOldExactMonthRequirement(){
    var rows=List.of(price(1002,"2026-10",null),price(1001,"2026-10","11"),price(999,"2026-09","7"));
    var same=calculate(rows,1001L,false);assertThat(same.getError()).isNull();assertThat(same.getResult()).isEqualByComparingTo("11");var older=calculate(rows,999L,false);assertThat(older.getError()).isNotBlank();assertThat(older.getResult()).isNull();
  }
  @Test void missingManagedPublicFactorCannotUseAValidPrivateMonthlyRow(){
    var result=calculate(List.of(price(1001,"2026-10","11")),null,true);assertThat(result.getError()).contains("财务方案缺少因素");assertThat(result.getResult()).isNull();
  }
  @Test void explicitZeroPrivateFactorIsDistinctFromAnUnavailableFactor(){
    var result=calculate(List.of(price(1001,"2026-10","0")),null,false);assertThat(result.getError()).isNull();assertThat(result.getResult()).isZero();assertThat(result.getInputSnapshotJson()).contains("usedPrivateFactors","1001");
  }
}
