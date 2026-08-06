package com.sanhua.marketingcost.service.materialshape;

import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.dates;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.policyResolution;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.sanhua.marketingcost.entity.SupplierSupplyRatio;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SupplierRatioShapeMonthTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierRatioShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    resolver = new SupplierRatioShapeResolverImpl(mapper);
  }

  @Test
  void expiredAndFutureRowsDoNotParticipateInCurrentMonth() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                dates(
                    row(31, "COMMERCIAL", "201850113", "SUP-OLD", "过期", "0.90"),
                    null,
                    LocalDate.of(2026, 7, 31)),
                dates(
                    row(32, "COMMERCIAL", "201850113", "SUP-FUTURE", "未来", "0.80"),
                    LocalDate.of(2026, 9, 1),
                    null),
                dates(
                    row(33, "COMMERCIAL", "201850113", "SUP-210", "本月", "0.60"),
                    LocalDate.of(2026, 8, 1),
                    LocalDate.of(2026, 8, 31))));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.selectedRatioRecordId()).isEqualTo(33L);
    ArgumentCaptor<QueryWrapper<SupplierSupplyRatio>> captor =
        ArgumentCaptor.forClass(QueryWrapper.class);
    verify(mapper).selectList(captor.capture());
    assertThat(captor.getValue().getSqlSegment())
        .contains("business_unit_type", "material_code", "effective_from", "effective_to", "deleted");
  }

  @Test
  void nullDateBoundsAreOpen() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(row(34, "COMMERCIAL", "201850113", "SUP-210", "长期有效", "1")));

    assertThat(
            resolver
                .resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"))
                .blocked())
        .isFalse();
  }

  @Test
  void zeroOrNegativeRatiosFallBackToOriginalU9Shape() {
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(35, "COMMERCIAL", "201850113", "SUP-ZERO", "零比例", "0"),
                row(36, "COMMERCIAL", "201850113", "SUP-NEG", "负比例", "-0.1")));

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    assertThat(result.effectiveShape()).isEqualTo(
        com.sanhua.marketingcost.enums.QuoteMaterialShape.MANUFACTURE);
    assertThat(result.selectedRatioRecordId()).isNull();
    assertThat(result.actionConfigJson()).isNull();
  }

  @Test
  void invalidAccountingMonthFailsBeforeQuerying() {
    assertThatThrownBy(
            () -> resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026/08")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("YYYY-MM");
  }
}
