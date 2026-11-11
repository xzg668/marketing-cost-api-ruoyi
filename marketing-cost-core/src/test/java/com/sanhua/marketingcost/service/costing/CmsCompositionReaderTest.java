package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.DepartmentFundRate;
import com.sanhua.marketingcost.service.pricing.monthly.MonthlyPriceDocumentCodec;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CmsCompositionReaderTest {
  final JdbcTemplate jdbc=mock(JdbcTemplate.class);
  final CmsCompositionReader reader=new CmsCompositionReader(jdbc);
  CostRunCostItemDto auxiliary(String ids) {
    var source=new AuxCostItemDto(); source.setSourceTable("cms_product_subject_cost_raw");
    source.setSourceRowIds(ids); source.setMaterialCode("1145900000611"); source.setSourcePeriod("2026-02");
    source.setAuxSubjectCode("0212"); source.setUnitPrice(new BigDecimal("0.221096"));
    var row=new CostRunCostItemDto(); row.setCostCode("AUX_0212"); row.setCostName("工量夹切工具");
    row.setBaseAmount(source.getUnitPrice()); row.setRate(new BigDecimal("1.05")); row.setAmount(new BigDecimal("0.232151"));
    row.setComposition(CmsCostCompositions.auxiliary(source)); return row;
  }
  Map<String,Object> source(long id,String parent,String amount) {
    return Map.of("id",id,"parent_code",parent,"period","2026-02","cents",new BigDecimal(amount).movePointRight(2),
        "yuan",new BigDecimal(amount),"path","1145900000611(×1)→TOOL(×1)→0212(自身)(22.1096)");
  }
  @Test void exactIdsAreBatchedOnceAndTheSavedCostIsNotRecalculated() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(source(57853080L,"1145900000611","0.221096")));
    var one=auxiliary("[57853080,57853080]"); var two=auxiliary("57853080");
    reader.complete(List.of(one,two),"COMMERCIAL");
    assertThat(one.getComposition().rows()).hasSize(1);
    assertThat(one.getComposition().rows().getFirst()).containsEntry("originalCent","22.1096").containsEntry("amountYuan","0.221096");
    assertThat(one.getAmount()).isEqualByComparingTo("0.232151");
    assertThat(one.getComposition().formula()).contains("不再重复乘BOM用量","上浮系数");
    verify(jdbc,times(1)).queryForList(contains("business_unit_type=? AND id IN (?)"),eq("COMMERCIAL"),eq(57853080L));
    assertThatThrownBy(()->one.getComposition().rows().getFirst().put("amountYuan","999")).isInstanceOf(UnsupportedOperationException.class);
  }
  @Test void MissingAndWrongParentSourcesAreExplicitAndNeverAdoptedAsDetail() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(source(1L,"OTHER","7")));
    var row=auxiliary("[1,2]"); reader.complete(List.of(row),"HOUSEHOLD");
    assertThat(row.getComposition().rows()).hasSize(2).allSatisfy(detail->{
      assertThat(detail).doesNotContainKey("amountYuan"); assertThat(detail.get("note")).contains("归属不匹配");
    });
    assertThat(row.getComposition().note()).contains("缺失或不匹配");
    assertThat(row.getAmount()).isEqualByComparingTo("0.232151");
  }
  @Test void ZeroAndNegativeOriginalRowsRemainVisibleAndMismatchesAreReported() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(
        source(1L,"1145900000611","0.000000"),source(2L,"1145900000611","-0.000001")));
    var row=auxiliary("1,2"); reader.complete(List.of(row),"COMMERCIAL");
    assertThat(row.getComposition().rows()).extracting(r->r.get("amountYuan")).containsExactly("0.000000","-0.000001");
    assertThat(row.getComposition().note()).contains("-0.000001","0.221096","不同");
  }
  @Test void SurfaceExclusionIsSavedWithTheOriginalAmountAndSourcePaths() {
    var source=new AuxCostItemDto(); source.setUnitPrice(new BigDecimal(".075788"));
    source.setExcludedSurfaceAmount(new BigDecimal("1.517485")); source.setExclusionEvidence("CMS #901：原始排除路径");
    var result=CmsCostCompositions.auxiliary(source);
    assertThat(result.sourceAmount()).isEqualTo("1.593273"); assertThat(result.excludedAmount()).isEqualTo("1.517485");
    assertThat(result.note()).contains("#901");
    var row=auxiliary(""); row.setComposition(result); reader.complete(List.of(row),"COMMERCIAL");
    verifyNoInteractions(jdbc);
  }
  @Test void DepartmentEvidenceCapturesManhourAndRateModeWithoutChangingNumbers() {
    var rate=new DepartmentFundRate(); rate.setId(12L); rate.setRateYear(2026); rate.setExpenseSubject("水电费");
    rate.setManhourRate(new BigDecimal(".4069")); rate.setQuoteRatio(new BigDecimal(".062993"));
    rate.setRateCalculationMode(DepartmentFundRate.RATE_CALCULATION_MODE_FINAL_QUOTE);
    var result=CmsCostCompositions.department(rate,new BigDecimal("4.692893"));
    assertThat(result.rows().getFirst()).containsEntry("manhourRate","0.4069").containsEntry("calculationMode","最终费率")
        .containsEntry("laborTotal","4.692893"); assertThat(result.formula()).contains("工时基数","最终费率模式");
  }
  @Test void EvidenceSurvivesConsumerRebindingAndOldSnapshotDocumentsStillRead() {
    var row=auxiliary("57853080");
    var resolved=new ResolvedOrdinaryCost(List.of(row),BigDecimal.ZERO,true);
    var rebound=resolved.bind(new ResolvedOrdinaryCost.Calculated(resolved.inputs(),resolved.inputs().rows())).getFirst();
    assertThat(rebound.getComposition()).isEqualTo(row.getComposition());
    var detail=MonthlyProductCost.OrdinaryDetail.from(rebound);
    var codec=new MonthlyPriceDocumentCodec(new ObjectMapper());
    assertThat(codec.decode(codec.encode(detail),MonthlyProductCost.OrdinaryDetail.class).composition()).isEqualTo(detail.composition());
    var old=new MonthlyProductCost.OrdinaryDetail("AUX_0212","工具",BigDecimal.ONE,null,BigDecimal.ONE,null,null,null,"EXPENSE");
    var oldJson=codec.unwrap(codec.encode(old)).replace(",\"composition\":null","");
    assertThat(codec.decode(codec.wrap(oldJson),MonthlyProductCost.OrdinaryDetail.class).composition()).isNull();
  }
}
