package com.sanhua.marketingcost.service.costing;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.sanhua.marketingcost.dto.AuxCostItemDto;
import com.sanhua.marketingcost.mapper.AuxCostItemMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class SinteredBaseSurfaceCostsTest {
  @Test void removesOnlyMatchingSubtreeBeforeUpliftAndPreservesOtherBranchesAndSubjects() {
    var mapper=mock(AuxCostItemMapper.class);var surface=row("0202","1.593273");
    when(mapper.surfacePaths(eq("COMMERCIAL"),any())).thenReturn(List.of(
      new AuxCostItemMapper.SurfacePath(1L,"P","2026-02","P(×1)→ASSEMBLY(×1)→BASE(×1)→020201(自身)(115.9882152)",new BigDecimal("1.159882")),
      new AuxCostItemMapper.SurfacePath(2L,"P","2026-02","P(×1)→BASE(×1)→0202(自身)(35.56869347);P(×1)→BASE(×1)→RAW(×1)→0202(自身)(0.04965205);P(×1)→BASE(×1)→BLANK(×1)→0202(自身)(0.142);P(×1)→OTHER(×1)→0202(自身)(7.5788)",new BigDecimal("0.433391"))));
    when(mapper.sinteredBaseCodes(eq("COMMERCIAL"),any())).thenReturn(List.of("BASE"));
    var other=row("0201","12");
    SinteredBaseSurfaceCosts.apply(List.of(surface,other),"COMMERCIAL",mapper);
    assertThat(surface.getUnitPrice()).isEqualByComparingTo("0.075788");
    assertThat(surface.getExcludedSurfaceAmount()).isEqualByComparingTo("1.517485");
    assertThat(surface.getUnitPrice().multiply(new BigDecimal("1.05")).setScale(6,java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("0.079577");
    assertThat(surface.getExclusionEvidence()).contains("BASE","BLANK").doesNotContain("OTHER");
    assertThat(other.getUnitPrice()).isEqualByComparingTo("12");
    SinteredBaseSurfaceCosts.apply(List.of(surface),"COMMERCIAL",mapper);
    assertThat(surface.getUnitPrice()).isEqualByComparingTo("0.075788");
  }
  @Test void noBaseKeepsAmountAndMissingReferencedPathCannotSilentlyInventADeduction() {
    var mapper=mock(AuxCostItemMapper.class);var surface=row("0202","2");
    when(mapper.surfacePaths(any(),any())).thenReturn(List.of());
    assertThatThrownBy(()->SinteredBaseSurfaceCosts.apply(List.of(surface),"COMMERCIAL",mapper))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("路径缺失");
    assertThat(surface.getUnitPrice()).isEqualByComparingTo("2");
  }
  private AuxCostItemDto row(String code,String amount) {
    var row=new AuxCostItemDto();row.setMaterialCode("P");row.setAuxSubjectCode(code);
    row.setSourceTable("cms_product_subject_cost_raw");
    row.setSource("CMS_EFFECTIVE");row.setSourceRowIds("1,2");row.setSourcePeriod("2026-02");row.setUnitPrice(new BigDecimal(amount));return row;
  }
}
