package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.*;
import com.sanhua.marketingcost.entity.*;
import com.sanhua.marketingcost.mapper.*;
import com.sanhua.marketingcost.service.jjb.JjbVersionAccess;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

class CostAmountCompositionServiceTest {
  final JjbVersionAccess access=mock(JjbVersionAccess.class);
  final MonthlyCostVersionService versions=mock(MonthlyCostVersionService.class);
  final CmsCostSourceEffectiveMapper salaries=mock(CmsCostSourceEffectiveMapper.class);
  final AuxCostItemMapper auxiliary=mock(AuxCostItemMapper.class);
  final CmsCompositionReader raw=mock(CmsCompositionReader.class);
  final CostAmountCompositionService service=new CostAmountCompositionService(access,versions,salaries,auxiliary,raw);
  final QuoteCostRunVersion version=new QuoteCostRunVersion();
  final CostRunCostItem item=new CostRunCostItem();
  CostAmountCompositionServiceTest() {
    version.setId(101L); version.setCostRunNo("SAVED-101"); version.setVersionNo("V4");
    version.setProductCode("1145900000611"); version.setPricingMonth("2026-11"); version.setBusinessUnitType("COMMERCIAL");
    item.setId(2695L); item.setCostRunVersionId(101L); item.setCostRunNo("SAVED-101"); item.setProductCode(version.getProductCode());
    item.setBusinessUnitType("COMMERCIAL"); item.setCostCode("AUX_0212"); item.setCostName("工量夹切工具");
    item.setBaseAmount(new BigDecimal(".221096")); item.setRate(new BigDecimal("1.05")); item.setAmount(new BigDecimal(".232151"));
    when(access.byRun("SAVED-101")).thenReturn(new JjbVersionAccess.Owned(version,null,null,"COMMERCIAL"));
    stored(null,List.of());
  }
  void stored(MonthlyCostVersionService.Snapshot snapshot,List<CostRunPartItem> parts) {
    when(versions.read("COMMERCIAL",101L)).thenReturn(new MonthlyCostVersionService.Stored(version,snapshot,parts,List.of(item)));
  }
  @Test void FrozenCompositionNeverQueriesTodaysCmsEvenAfterItsSourcesChange() {
    var evidence=new CostAmountComposition("SAVED","来源合计×1.05",".221096","0",null,List.of(),List.of(Map.of("sourceId","57853080","amountYuan",".221096")));
    var zero=BigDecimal.ZERO;
    var ordinary=new MonthlyProductCost.Ordinary(List.of(),zero,zero,zero,zero,zero,BigDecimal.ONE,zero,zero,zero,zero,
        List.of(new MonthlyProductCost.OrdinaryDetail("AUX_0212","工量夹切工具",item.getBaseAmount(),item.getRate(),item.getAmount(),null,null,null,"EXPENSE",evidence)));
    var calculation=new MonthlyProductCost.Result("V3","FEE",ordinary,null,List.of(),List.of(),Set.of(),List.of(),List.of(),zero,zero,zero,zero,zero,zero,zero,null);
    var snapshot=new MonthlyCostVersionService.Snapshot(1,"COMMERCIAL",101,"SAVED-101","OA",1,"1145900000611","2026-11",calculation,List.of(),Map.of());
    item.setRate(new BigDecimal("1.050000"));
    stored(snapshot,List.of());
    var result=service.read("SAVED-101",2695L,null);
    assertThat(result.composition()).isEqualTo(evidence); assertThat(result.amount()).isEqualTo("0.232151");
    assertThat(result.rate()).isEqualTo("1.05");
    verifyNoInteractions(salaries,auxiliary,raw);
  }
  @Test void OldVersionShowsCurrentSourcesAndDifferenceWhileRetainingSavedAmount() {
    var source=new AuxCostItemDto(); source.setAuxSubjectCode("0212"); source.setMaterialCode(version.getProductCode());
    source.setUnitPrice(new BigDecimal(".3")); source.setSourceTable("cms_product_subject_cost_raw");
    source.setSourcePeriod("2026-02"); source.setSourceRowIds("57853080");
    when(auxiliary.selectEffectiveAuxCostItems(2026,List.of(version.getProductCode()),"COMMERCIAL","2026-11")).thenReturn(List.of(source));
    var result=service.read("SAVED-101",2695L,null);
    assertThat(result.amount()).isEqualTo("0.232151"); assertThat(result.referenceAmount()).isEqualTo("0.315000");
    assertThat(result.difference()).isEqualTo("0.082849"); assertThat(result.composition().origin()).isEqualTo("CURRENT_REFERENCE");
    assertThat(result.composition().note()).contains("旧版本未保存");
  }
  @Test void TechnicalSalaryIsNotMisrepresentedAsCms() {
    item.setCostCode("DIRECT_LABOR"); item.setSourceTable("lp_quote_tech_data_version"); item.setSourceId(90L);
    assertThat(service.read("SAVED-101",2695L,null).composition().rows().getFirst()).containsEntry("sourceId","90");
    verifyNoInteractions(salaries,auxiliary,raw);
  }
  @Test void CostIdsAndPartIdsAreScopedToTheSelectedSavedVersionBeforeAnyCmsRead() {
    assertThatThrownBy(()->service.read("SAVED-101",8L,null)).isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(()->service.read("SAVED-101",null,8L)).isInstanceOf(AccessDeniedException.class);
    item.setBusinessUnitType("HOUSEHOLD");
    assertThatThrownBy(()->service.read("SAVED-101",2695L,null)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(salaries,auxiliary,raw);
  }
  @Test void AccessIsEnforcedBeforeReadingAnotherCostVersion() {
    when(access.byRun("OTHER")).thenThrow(new AccessDeniedException("拒绝"));
    assertThatThrownBy(()->service.read("OTHER",2695L,null)).isInstanceOf(AccessDeniedException.class);
    verifyNoInteractions(salaries,auxiliary,raw); verify(versions,never()).read(anyString(),anyLong());
  }
  @Test void ExactlyOneValidSavedAmountSelectorIsRequired() {
    assertThatThrownBy(()->service.read("SAVED-101",2695L,3L)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.read("SAVED-101",null,null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.read("SAVED-101",-1L,null)).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(access,salaries,auxiliary,raw);
  }
  @Test void SolderUsesSavedPartQuantityAndPriceWithoutAnotherCostBucket() {
    var part=new CostRunPartItem(); part.setId(500L); part.setCostRunVersionId(101L); part.setCostRunNo("SAVED-101");
    part.setBusinessUnitType("COMMERCIAL"); part.setProductCode(version.getProductCode()); part.setPartCode("337106891");
    part.setPartName("焊料"); part.setUnitPrice(new BigDecimal("910.85")); part.setQty(new BigDecimal(".00065961")); part.setAmount(new BigDecimal(".600817"));
    stored(null,List.of(part)); var result=service.read("SAVED-101",null,500L);
    assertThat(result.composition().rows().getFirst()).containsEntry("quantity","0.00065961").containsEntry("unitPrice","910.85");
    assertThat(result.composition().formula()).contains("只计入一次"); verifyNoInteractions(salaries,auxiliary,raw);
  }
  @Test void OldWaterCostExplainsSavedLaborAndHoursAndLabelsTodaysRateAsReferenceOnly() {
    item.setCostCode("WATER_POWER");item.setCostName("水电费");item.setBaseAmount(new BigDecimal("11.533283"));
    item.setRate(new BigDecimal(".062993"));item.setAmount(new BigDecimal(".726516"));
    var direct=new CostRunCostItem();direct.setCostCode("DIRECT_LABOR");direct.setAmount(new BigDecimal("4.601193"));
    var indirect=new CostRunCostItem();indirect.setCostCode("INDIRECT_LABOR");indirect.setAmount(new BigDecimal(".091700"));
    when(versions.read("COMMERCIAL",101L)).thenReturn(new MonthlyCostVersionService.Stored(version,null,List.of(),List.of(item,direct,indirect)));
    var jdbc=mock(org.springframework.jdbc.core.JdbcTemplate.class);
    org.springframework.test.util.ReflectionTestUtils.setField(service,"jdbc",jdbc);
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(Map.of("id",12L,"rate_year",2026,
        "business_division","商用部品事业部","expense_subject","水电费","manhour_rate",new BigDecimal(".4069"),
        "quote_ratio",new BigDecimal(".062993"),"uplift_ratio",BigDecimal.ONE,"rate_calculation_mode","FINAL_QUOTE")));
    var result=service.read("SAVED-101",2695L,null);
    assertThat(result.calculation()).contains("4.601193 + 辅助人工 0.091700 = 人工合计 4.692893", "11.533283 × 0.062993 = 0.726516", "当时工时工资率未记录");
    assertThat(result.composition().rows()).anySatisfy(row->{
      assertThat(row).containsEntry("manhourRate","0.4069").containsEntry("calculationMode","最终费率");
      assertThat(row.get("note")).contains("不代表当时采用值");
    });
    verify(jdbc).queryForList(contains("d.business_unit_type=?"),eq(2026),eq("COMMERCIAL"),eq("1145900000611"),eq("水电费用"),eq("水电费"),eq("水电"));
  }
}
