package com.sanhua.marketingcost.service.materialshape;

import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.policyResolution;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplierRatioShapeTieBlockTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierRatioShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    resolver = new SupplierRatioShapeResolverImpl(mapper);
  }

  @Test
  void equalMaximumRatiosBlockInsteadOfGuessingByIdOrTime() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(20, "COMMERCIAL", "201850113", "SUP-A", "供应商A", "0.50"),
                row(99, "COMMERCIAL", "201850113", "SUP-B", "供应商B", "0.500000")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isTrue();
    assertThat(result.effectiveShape()).isNull();
    assertThat(result.selectedRatioRecordId()).isNull();
    assertThat(result.blockingReason()).contains("并列", "0.50", "SUP-A", "SUP-B");
  }

  @Test
  void duplicateEffectiveRowsForSameSupplierCodeBlock() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(21, "COMMERCIAL", "201850113", "SUP-A", "供应商A旧名", "0.60"),
                row(22, "COMMERCIAL", "201850113", "sup-a", "供应商A新名", "0.40")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isTrue();
    assertThat(result.blockingReason()).contains("同一供应商", "重复", "SUP-A");
  }

  @Test
  void missingSupplierCodeStillUsesRelationshipShape() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(23, "COMMERCIAL", "201850113", null, "无编码供应商", "0.70"),
                row(24, "COMMERCIAL", "201850113", "SUP-B", "供应商B", "0.30")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    assertThat(result.selectedSupplierCode()).isNull();
    assertThat(result.selectedSupplierName()).isEqualTo("无编码供应商");
    assertThat(result.effectiveShape()).isEqualTo(
        com.sanhua.marketingcost.enums.QuoteMaterialShape.OUTSOURCE);
  }

  @Test
  void noRowsFallBackToOriginalU9ShapeWithoutRunningOutsourceAction() {
    when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of());

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(
        com.sanhua.marketingcost.enums.QuoteMaterialShape.MANUFACTURE);
    assertThat(result.selectedRatioRecordId()).isNull();
    assertThat(result.selectedSupplierCode()).isNull();
    assertThat(result.actionConfigJson()).isNull();
  }
}
