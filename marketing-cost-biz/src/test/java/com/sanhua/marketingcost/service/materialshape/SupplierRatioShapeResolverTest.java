package com.sanhua.marketingcost.service.materialshape;

import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.policyResolution;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.row;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.shape;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplierRatioShapeResolverTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierRatioShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    resolver = new SupplierRatioShapeResolverImpl(mapper);
  }

  @Test
  void sixtyThirtyTenSelectsSixtyAndKeepsCompleteEvidence() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(1, "COMMERCIAL", "201850113", "SUP-210", "三花商用制冷", "0.60"),
                row(2, "COMMERCIAL", "201850113", "SUP-B", "供应商B", "0.30"),
                row(3, "COMMERCIAL", "201850113", "SUP-C", "供应商C", "0.10")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    assertThat(result.materialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(result.priceOrgCode()).isEqualTo("210");
    assertThat(result.effectiveShape()).isEqualTo(QuoteMaterialShape.MANUFACTURE);
    assertThat(result.policyId()).isEqualTo(81L);
    assertThat(result.policyFingerprint()).isEqualTo("policy-fingerprint");
    assertThat(result.selectedRatioRecordId()).isEqualTo(1L);
    assertThat(result.selectedSupplierCode()).isEqualTo("SUP-210");
    assertThat(result.selectedSupplierName()).isEqualTo("三花商用制冷");
    assertThat(result.selectedSupplyRatio()).isEqualByComparingTo("0.60");
  }

  @Test
  void purchaseRelationshipUsesOutsourceShape() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(4, "COMMERCIAL", "201850113", "SUP-EXT", "外部供应商", "0.80"),
                row(5, "COMMERCIAL", "201850113", "SUP-210", "内部供应商", "0.20")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    assertThat(result.internalSupplier()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(QuoteMaterialShape.OUTSOURCE);
    assertThat(result.actionConfigJson()).contains("311034930");
  }

  @Test
  void relationshipShapeWinsInsteadOfConfiguredSupplierCodeOrName() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                shape(
                    row(
                        6,
                        "COMMERCIAL",
                        "201850113",
                        "SUP-210",
                        "三花商用制冷",
                        "1.0"),
                    "采购件")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.internalSupplier()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(QuoteMaterialShape.OUTSOURCE);
  }

  @Test
  void missingRelationshipShapeBlocksWithSourceRecordEvidence() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                shape(
                    row(
                        61,
                        "COMMERCIAL",
                        "201850113",
                        null,
                        "未维护形态的供应商",
                        "1.0"),
                    null)));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockingReason()).contains("形态属性", "记录ID=61");
  }

  @Test
  void ratioComparisonKeepsDecimalPrecision() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(7, "COMMERCIAL", "201850113", "SUP-A", "供应商A", "0.333333"),
                row(8, "COMMERCIAL", "201850113", "SUP-220", "供应商B", "0.333334")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.selectedRatioRecordId()).isEqualTo(8L);
    assertThat(result.selectedSupplyRatio()).isEqualByComparingTo("0.333334");
  }

  @Test
  void oneHundredSpecialMaterialsUseOneSupplierRatioQuery() {
    List<MaterialQuoteShapeResolution> policies =
        IntStream.range(0, 100)
            .mapToObj(
                index -> policyResolution("COMMERCIAL", "SPECIAL-" + index, "2026-08"))
            .toList();
    List<SupplierSupplyRatio> ratios =
        IntStream.range(0, 100)
            .mapToObj(
                index ->
                    row(
                        1_000L + index,
                        "COMMERCIAL",
                        "SPECIAL-" + index,
                        "SUP-210",
                        "三花商用制冷",
                        "1"))
            .toList();
    when(mapper.selectList(any(Wrapper.class))).thenReturn(ratios);

    Map<String, SupplierRatioResolution> results = resolver.resolveAll(policies);

    assertThat(results).hasSize(100);
    assertThat(results.values())
        .allMatch(result -> result.effectiveShape() == QuoteMaterialShape.MANUFACTURE);
    verify(mapper, times(1)).selectList(any(Wrapper.class));
  }
}
