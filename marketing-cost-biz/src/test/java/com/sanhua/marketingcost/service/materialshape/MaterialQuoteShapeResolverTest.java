package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class MaterialQuoteShapeResolverTest {

  private MaterialQuoteShapePolicyMapper mapper;
  private MaterialQuoteShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(MaterialQuoteShapePolicyMapper.class);
    resolver =
        new MaterialQuoteShapeResolverImpl(
            mapper, new ShapePolicyFingerprint(new ObjectMapper()));
    when(mapper.selectList(any())).thenReturn(List.of());
  }

  @ParameterizedTest(name = "U9原值 {0} 标准化为 {1}")
  @MethodSource("u9Shapes")
  @DisplayName("无规则时制造、采购、委外、虚拟完全沿用 U9")
  void noPolicyUsesNormalizedU9(
      String u9Shape, QuoteMaterialShape expected) {
    MaterialQuoteShapeResolution result = resolve(u9Shape);

    assertThat(result.blocked()).isFalse();
    assertThat(result.sourceU9Shape()).isEqualTo(u9Shape);
    assertThat(result.normalizedU9Shape()).isEqualTo(expected);
    assertThat(result.effectiveShape()).isEqualTo(expected);
    assertThat(result.source()).isEqualTo(MaterialQuoteShapeSource.U9);
    assertThat(result.policyId()).isNull();
    assertThat(result.policyFingerprint()).isNull();
  }

  @ParameterizedTest(name = "{0} 固定改为 {1}")
  @MethodSource("fixedChanges")
  @DisplayName("FIXED 规则覆盖 U9，可覆盖为三个可配置报价形态")
  void fixedPolicyOverridesU9(
      String u9Shape, String targetShape, QuoteMaterialShape expected) {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.fixed(
            11L, "COMMERCIAL", "A-100", targetShape, "2026-08", null);
    when(mapper.selectList(any())).thenReturn(List.of(policy));

    MaterialQuoteShapeResolution result = resolve(u9Shape);

    assertThat(result.blocked()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(expected);
    assertThat(result.source())
        .isEqualTo(MaterialQuoteShapeSource.FIXED_POLICY);
    assertThat(result.policyId()).isEqualTo(11L);
    assertThat(result.policyFingerprint()).hasSize(64);
    assertThat(result.conditionConfigJson()).isNull();
    assertThat(result.actionConfigJson()).isNull();
  }

  @Test
  @DisplayName("历史 VIRTUAL 固定规则仍可解析，避免旧数据导致报价失败")
  void legacyVirtualFixedPolicyRemainsReadable() {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.fixed(
            12L, "COMMERCIAL", "A-100", "VIRTUAL", "2026-08", null);
    when(mapper.selectList(any())).thenReturn(List.of(policy));

    MaterialQuoteShapeResolution result = resolve("采购件");

    assertThat(result.blocked()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(QuoteMaterialShape.VIRTUAL);
    assertThat(result.source()).isEqualTo(MaterialQuoteShapeSource.FIXED_POLICY);
  }

  @Test
  @DisplayName("命中 SUPPLIER_RATIO 时保留规则证据并阻断到 QEB-05，不猜最终形态")
  void supplierRatioPolicyWaitsForDedicatedResolver() {
    MaterialQuoteShapePolicy policy =
        MaterialQuoteShapeTestSupport.supplierRatio(
            21L, "COMMERCIAL", "A-100", "2026-08");
    when(mapper.selectList(any())).thenReturn(List.of(policy));

    MaterialQuoteShapeResolution result = resolve("制造件");

    assertThat(result.blocked()).isTrue();
    assertThat(result.effectiveShape()).isNull();
    assertThat(result.source())
        .isEqualTo(MaterialQuoteShapeSource.SUPPLIER_RATIO);
    assertThat(result.policyId()).isEqualTo(21L);
    assertThat(result.policyFingerprint()).hasSize(64);
    assertThat(result.conditionConfigJson()).contains("SUP-210", "SUP-220");
    assertThat(result.actionConfigJson()).contains("311034930");
    assertThat(result.blockingReason()).contains("供货比例", "QEB-05");
  }

  @Test
  @DisplayName("无规则且 U9 形态未知时返回明确阻断原因")
  void unknownU9ShapeIsBlocked() {
    MaterialQuoteShapeResolution result = resolve("半成品-未知");

    assertThat(result.blocked()).isTrue();
    assertThat(result.effectiveShape()).isNull();
    assertThat(result.source()).isEqualTo(MaterialQuoteShapeSource.U9);
    assertThat(result.blockingReason())
        .contains("无法识别", "半成品-未知", "A-100");
  }

  @Test
  @DisplayName("同组织、料号、月份命中多条启用规则时明确失败")
  void conflictingPoliciesFailFast() {
    when(mapper.selectList(any()))
        .thenReturn(
            List.of(
                MaterialQuoteShapeTestSupport.fixed(
                    1L, "COMMERCIAL", "A-100", "PURCHASE", "2026-08", null),
                MaterialQuoteShapeTestSupport.fixed(
                    2L, "COMMERCIAL", "A-100", "MANUFACTURE", "2026-08", null)));

    assertThatThrownBy(() -> resolve("制造件"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("命中多条", "A-100", "2026-08");
  }

  @Test
  @DisplayName("1000 个料号批量解析只读取一次形态规则表")
  void oneThousandMaterialsUseOnePolicyQuery() {
    List<MaterialQuoteShapeRequest> requests =
        IntStream.range(0, 1_000)
            .mapToObj(
                index ->
                    new MaterialQuoteShapeRequest(
                        "COMMERCIAL", "M-" + index, "2026-08", "采购件"))
            .toList();

    Map<String, MaterialQuoteShapeResolution> results = resolver.resolveAll(requests);

    assertThat(results).hasSize(1_000);
    assertThat(results.values())
        .allMatch(result -> result.effectiveShape() == QuoteMaterialShape.PURCHASE);
    verify(mapper, times(1)).selectList(any());
  }

  private MaterialQuoteShapeResolution resolve(String u9Shape) {
    return resolver.resolve(
        new MaterialQuoteShapeRequest(
            "COMMERCIAL", "A-100", "2026-08", u9Shape));
  }

  private static Stream<Arguments> u9Shapes() {
    return Stream.of(
        Arguments.of("制造件", QuoteMaterialShape.MANUFACTURE),
        Arguments.of("采购件", QuoteMaterialShape.PURCHASE),
        Arguments.of("委外件", QuoteMaterialShape.OUTSOURCE),
        Arguments.of("虚拟", QuoteMaterialShape.VIRTUAL));
  }

  private static Stream<Arguments> fixedChanges() {
    return Stream.of(
        Arguments.of("制造件", "PURCHASE", QuoteMaterialShape.PURCHASE),
        Arguments.of("采购件", "MANUFACTURE", QuoteMaterialShape.MANUFACTURE),
        Arguments.of("制造件", "OUTSOURCE", QuoteMaterialShape.OUTSOURCE));
  }
}
