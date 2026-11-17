package com.sanhua.marketingcost.service;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.pricing.monthly.*;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyAdoptionPlan.*;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.*;
import org.junit.jupiter.api.Test;

public class MakePartFinancialPricesTest {
  private final MonthlyPriceDocumentCodec codec = new MonthlyPriceDocumentCodec(new ObjectMapper());
  @Test void actualCopperInputsProduceFinancePriceWithoutChangingQuoteRows() {
    var original = row("one");
    var before = codec.encode(original);
    var actual = new MakePartFinancialPrices(codec).project(List.of(original)).getFirst();
    assertThat(actual.getCostPrice()).isEqualByComparingTo("1.86645971");
    assertThat(actual.getParentTotalCostPrice()).isEqualByComparingTo("1.86645971");
    assertThat(actual.getRemark()).doesNotContain("110.044247").contains("83.495575");
    assertThat(codec.encode(original)).isEqualTo(before);
    assertThat(actual.getId()).isEqualTo(original.getId());
  }
  @Test void repeatedBatchesNeverSumIntoEachOtherAndProjectionIsIdempotent() {
    var first=row("one"); var second=row("two");
    var projection=new MakePartFinancialPrices(codec);
    var projected=projection.project(List.of(first,second));
    assertThat(projected).allSatisfy(r->assertThat(r.getParentTotalCostPrice()).isEqualByComparingTo("1.86645971"));
    assertThat(codec.encode(projection.project(projected))).isEqualTo(codec.encode(projected));
  }
  @Test void multipleInputsSumOnlyTheirOwnFinancialAmounts() {
    var first=row("one"); var second=row("one"); second.setId(2L);
    var result=new MakePartFinancialPrices(codec).project(List.of(first,second));
    assertThat(result).allSatisfy(r->assertThat(r.getParentTotalCostPrice()).isEqualByComparingTo("3.73291942"));
  }
  @Test void missingOrMismatchedEvidenceNeverRelabelsCustomerPriceAsFinance() {
    var row=row("one"); row.setRawInputSnapshotJson(null);
    assertThatThrownBy(()->new MakePartFinancialPrices(codec).project(List.of(row))).hasMessageContaining("缺少");
    row.setRawInputSnapshotJson(price("OTHER","10","20","2026-11","1"));
    assertThatThrownBy(()->new MakePartFinancialPrices(codec).project(List.of(row))).hasMessageContaining("身份不一致");
  }
  @Test void blankPerPieceAndUnitMultiplierAndConfirmedZeroScrapAreRetained() {
    var row=row("one"); row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_BLANK);
    row.setRawInputSnapshotJson(price("RAW","2","3","2026-11","1000"));
    row.setNoScrapConfirmed(true); row.setScrapCode(""); row.setScrapInputSnapshotJson(null); row.setScrapUnitPrice(BigDecimal.ZERO);
    var result=new MakePartFinancialPrices(codec).project(List.of(row)).getFirst();
    assertThat(result.getCostPrice()).isEqualByComparingTo("2000");
  }
  @Test void existingGapRemainsAGap() {
    var row=row("one"); row.setStatus("MISSING_RAW_PRICE"); row.setRawUnitPrice(null); row.setRawInputSnapshotJson(null);
    var result=new MakePartFinancialPrices(codec).project(List.of(row)).getFirst();
    assertThat(result.getStatus()).isEqualTo("MISSING_RAW_PRICE"); assertThat(result.getCostPrice()).isNull();
  }
  private MakePartPriceCalcRow row(String batch) {
    var row=new MakePartPriceCalcRow(); row.setId(1L);row.setCalcBatchId(batch);row.setSourceCostingRowId(10L);
    row.setParentMaterialNo("MAKE");row.setChildMaterialNo("RAW");row.setScrapCode("SCRAP");
    row.setBusinessUnitType("COMMERCIAL");row.setPricingMonth("2026-11");row.setPriceScenarioType("OA_LOCKED");
    row.setItemProcessType(MakePartProcessTypePolicy.PROCESS_TYPE_RAW);row.setStockUnit("kg");
    row.setQtyPerParent(new BigDecimal(".02349052"));row.setGrossWeightG(new BigDecimal("23.49052"));row.setNetWeightG(new BigDecimal("22.25585"));
    row.setRawUnitPrice(new BigDecimal("110.044247787610619469"));row.setScrapUnitPrice(new BigDecimal("102.477876106194690265"));
    row.setRawInputSnapshotJson(price("RAW","83.495575221238938053","110.044247787610619469","2026-11","1"));
    row.setScrapInputSnapshotJson(price("SCRAP","76.858407079646017699","102.477876106194690265","2026-11","1"));
    row.setStatus("OK");return new MakePartPriceCalculator().calculate(List.of(row)).getFirst();
  }
  public static String price(String code,String p0,String p1,String month,String multiplier) {
    var request=new MonthlyPriceRequest(new MonthlyPriceFamilyKey(YearMonth.parse(month),"COMMERCIAL",code,null,"kg","CNY",TaxBasis.EXCLUDED,null),"210",NonCuConditions.empty(),BigDecimal.ONE);
    var selection=new Selection(1L,new MonthlyPriceCoverage(Set.of("210"),QuantityInterval.all(),ConditionDomain.any()),
        PriceFact.normal(new BigDecimal(p0),SourceEvidence.ofJson("{\"source\":\"test\"}"),Set.of()));
    return new MonthlyPriceDocumentCodec(new ObjectMapper()).encode(MonthlyPreparationPrice.read(new QuotationPriceSession.Value(request,selection,new BigDecimal(p0),new BigDecimal(p1))).withUnitPriceMultiplier(new BigDecimal(multiplier)));
  }
}
