package com.sanhua.marketingcost.service.materialshape;

import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.policyResolution;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SupplierRatioShapeOrganizationIsolationTest {

  private SupplierSupplyRatioMapper mapper;
  private SupplierRatioShapeResolver resolver;

  @BeforeEach
  void setUp() {
    mapper = mock(SupplierSupplyRatioMapper.class);
    resolver = new SupplierRatioShapeResolverImpl(mapper);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(41, "COMMERCIAL", "201850113", "SUP-210", "商用主供", "0.60"),
                row(42, "PLATE", "201850113", "SUP-220", "板换主供", "0.80")));
  }

  @Test
  void commercial210AndPlate220NeverShareRows() {
    SupplierRatioResolution commercial =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));
    SupplierRatioResolution plate =
        resolver.resolve(policyResolution("PLATE", "201850113", "2026-08"));

    assertThat(commercial.priceOrgCode()).isEqualTo("210");
    assertThat(commercial.selectedRatioRecordId()).isEqualTo(41L);
    assertThat(plate.priceOrgCode()).isEqualTo("220");
    assertThat(plate.selectedRatioRecordId()).isEqualTo(42L);
  }

  @Test
  void organizationLabelsNormalizeToStableCodes() {
    SupplierRatioResolution commercial =
        resolver.resolve(policyResolution("商用", "201850113", "2026-08"));
    SupplierRatioResolution plate =
        resolver.resolve(policyResolution("板换", "201850113", "2026-08"));

    assertThat(commercial.materialOrganizationCode()).isEqualTo("COMMERCIAL");
    assertThat(plate.materialOrganizationCode()).isEqualTo("PLATE");
  }

  @Test
  void unsupportedOrganizationFailsClosed() {
    assertThatThrownBy(
            () -> resolver.resolve(policyResolution("UNKNOWN", "201850113", "2026-08")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("COMMERCIAL", "PLATE");
  }
}
