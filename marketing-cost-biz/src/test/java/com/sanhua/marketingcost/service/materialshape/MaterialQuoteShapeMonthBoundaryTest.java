package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaterialQuoteShapeMonthBoundaryTest {

  private MaterialQuoteShapePolicyMapper mapper;
  private MaterialQuoteShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(MaterialQuoteShapePolicyMapper.class);
    resolver =
        new MaterialQuoteShapeResolverImpl(
            mapper, new ShapePolicyFingerprint(new ObjectMapper()));
  }

  @Test
  @DisplayName("FIXED 在生效首月和结束月命中，之前和之后回退 U9")
  void fixedPolicyUsesInclusiveMonthRange() {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.fixed(
            1L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", "2026-10");
    when(mapper.selectList(any())).thenReturn(List.of(policy));

    assertThat(resolve("2026-07").source()).isEqualTo(MaterialQuoteShapeSource.U9);
    assertThat(resolve("2026-08").source())
        .isEqualTo(MaterialQuoteShapeSource.FIXED_POLICY);
    assertThat(resolve("2026-10").source())
        .isEqualTo(MaterialQuoteShapeSource.FIXED_POLICY);
    assertThat(resolve("2026-11").source()).isEqualTo(MaterialQuoteShapeSource.U9);
  }

  @Test
  @DisplayName("8 月中维护但 9 月生效的规则不影响 8 月解析")
  void nextMonthPolicyDoesNotAffectCurrentMonth() {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.fixed(
            2L, "COMMERCIAL", "A-100", "PURCHASE", "2026-09", null);
    when(mapper.selectList(any())).thenReturn(List.of(policy));

    MaterialQuoteShapeResolution august = resolve("2026-08");
    MaterialQuoteShapeResolution september = resolve("2026-09");

    assertThat(august.source()).isEqualTo(MaterialQuoteShapeSource.U9);
    assertThat(august.effectiveShape())
        .isEqualTo(QuoteMaterialShape.MANUFACTURE);
    assertThat(september.source())
        .isEqualTo(MaterialQuoteShapeSource.FIXED_POLICY);
    assertThat(september.effectiveShape())
        .isEqualTo(QuoteMaterialShape.PURCHASE);
  }

  @Test
  @DisplayName("规则按 U9 组织隔离，板换规则不能影响商用解析")
  void materialOrganizationIsIsolated() {
    MaterialQuoteShapePolicy platePolicy =
        MaterialQuoteShapeTestSupport.fixed(
            3L, "PLATE", "A-100", "PURCHASE", "2026-08", null);
    when(mapper.selectList(any())).thenReturn(List.of(platePolicy));

    MaterialQuoteShapeResolution result = resolve("2026-08");

    assertThat(result.source()).isEqualTo(MaterialQuoteShapeSource.U9);
    assertThat(result.effectiveShape())
        .isEqualTo(QuoteMaterialShape.MANUFACTURE);
  }

  @Test
  @DisplayName("停用规则即使月份命中也不参与解析")
  void disabledPolicyIsIgnored() {
    MaterialQuoteShapePolicy disabled =
        MaterialQuoteShapeTestSupport.fixed(
            4L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null);
    disabled.setEnabled(MaterialQuoteShapePolicy.DISABLED);
    when(mapper.selectList(any())).thenReturn(List.of(disabled));

    assertThat(resolve("2026-08").source()).isEqualTo(MaterialQuoteShapeSource.U9);
  }

  @Test
  @DisplayName("月份必须严格使用 YYYY-MM")
  void monthMustBeStrict() {
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> resolve("2026-8"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
  }

  private MaterialQuoteShapeResolution resolve(String month) {
    return resolver.resolve(
        new MaterialQuoteShapeRequest(
            "COMMERCIAL", "A-100", month, "制造件"));
  }
}
