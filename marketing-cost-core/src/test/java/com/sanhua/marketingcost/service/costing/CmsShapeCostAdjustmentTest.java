package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.entity.CmsCostSourceEffective;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CmsShapeCostAdjustmentTest {
  JdbcTemplate jdbc=mock(JdbcTemplate.class);
  CmsShapeCostAdjustment adjustment=new CmsShapeCostAdjustment(jdbc);
  private Map<String,Object> row(String path,String amount) {
    return Map.of("id",10L,"parent_code","P","period","2026-11","path",path,"amount_yuan",new BigDecimal(amount));
  }
  private CmsCostSourceEffective salary(String amount) {
    var s=new CmsCostSourceEffective();s.setParentCode("P");s.setPeriod("2026-11");
    s.setSourceTable("cms_workshop_labor_raw");s.setSourceRowIds("10");s.setAmountYuan(new BigDecimal(amount));return s;
  }
  private AuxCostItemDto auxiliary(String amount) {
    var s=new AuxCostItemDto();s.setMaterialCode("P");s.setSourcePeriod("2026-11");s.setSource("CMS_EFFECTIVE");
    s.setSourceTable("cms_product_subject_cost_raw");s.setSourceRowIds("10");s.setUnitPrice(new BigDecimal(amount));return s;
  }
  @Test void deductsChangedMaterialsOwnHoursAndKeepsChildAndOtherBranch() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(
        "P(×1)→A(×2)→WORK(自身)(20);P(×1)→A(×2)→C(×1)→WORK(自身)(10);P(×1)→A10(×1)→WORK(自身)(10)","8")));
    var original=salary("8");var result=adjustment.salary(original,"COMMERCIAL",Map.of("A",new BigDecimal("2")));
    assertThat(result.getAmountYuan()).isEqualByComparingTo("4");
    assertThat(result.getShapeExcludedAmount()).isEqualByComparingTo("4");
    assertThat(result.getShapeExclusionRows().getFirst().get("path")).isEqualTo("P(×1)→A(×2)→WORK(自身)(20)");
    assertThat(original.getAmountYuan()).isEqualByComparingTo("8");assertThat(original.getShapeExcludedAmount()).isNull();
  }
  @Test void quantitiesAlreadyInCmsPathsAreNotMultipliedAgain() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row("P(×1)→A(×2)→WORK(自身)(20)","4")));
    var result=adjustment.salary(salary("4"),"COMMERCIAL",Map.of("A",new BigDecimal("2")));
    assertThat(result.getAmountYuan()).isEqualByComparingTo("0");
    assertThat(result.getShapeExcludedAmount()).isEqualByComparingTo("4");
  }
  @Test void auxiliaryOnlyDeductsOwnShareBeforeUplift() {
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(
        "P(×1)→A(×1)→0201(自身)(200);P(×1)→A(×1)→C(×1)→0201(自身)(300)","5")));
    var original=auxiliary("5");var result=adjustment.auxiliary(original,"COMMERCIAL",Map.of("A",BigDecimal.ONE));
    assertThat(result.getUnitPrice()).isEqualByComparingTo("3");
    assertThat(result.getShapeExcludedAmount()).isEqualByComparingTo("2");
    assertThat(original.getUnitPrice()).isEqualByComparingTo("5");
    assertThat(CmsCostCompositions.auxiliary(result).excludedAmount()).isEqualTo("2.000000");
  }
  @Test void existingSurfaceExclusionIsNotDeductedTwice() {
    String own="P(×1)→A(×1)→0202(自身)(200)";
    when(jdbc.queryForList(anyString(),any(Object[].class))).thenReturn(List.of(row(own+";P(×1)→C(×1)→0202(自身)(300)","5")));
    var source=auxiliary("3");source.setExcludedSurfaceAmount(new BigDecimal("2"));source.setExclusionEvidence("CMS #10："+own);
    assertThat(adjustment.auxiliary(source,"COMMERCIAL",Map.of("A",BigDecimal.ONE))).isSameAs(source);
  }
  @Test void missingSourceIdsBlocksInsteadOfDeductingUnrelatedTotal() {
    var source=salary("2");source.setSourceRowIds(null);
    assertThatThrownBy(()->adjustment.salary(source,"COMMERCIAL",Map.of("A",BigDecimal.ONE)))
        .hasMessageContaining("料号A").hasMessageContaining("CMS原始明细ID");
  }
  @Test void noShapeChangesRetainsExistingPathWithoutAdditionalReads() {
    var source=salary("8");assertThat(adjustment.salary(source,"COMMERCIAL",Map.of())).isSameAs(source);
    verifyNoInteractions(jdbc);
  }
}
