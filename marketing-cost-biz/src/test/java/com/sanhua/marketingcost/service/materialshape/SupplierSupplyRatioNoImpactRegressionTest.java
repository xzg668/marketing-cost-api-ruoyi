package com.sanhua.marketingcost.service.materialshape;

import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.policyResolution;
import static com.sanhua.marketingcost.service.materialshape.SupplierRatioShapeTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.sanhua.marketingcost.mapper.SupplierSupplyRatioMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupplierSupplyRatioNoImpactRegressionTest {

  @Test
  void qebResolverUsesOneReadAndNeverMutatesExistingSupplyRatioData() {
    SupplierSupplyRatioMapper mapper = mock(SupplierSupplyRatioMapper.class);
    when(mapper.selectList(any(Wrapper.class)))
        .thenReturn(
            List.of(
                row(51, "COMMERCIAL", "201850113", "SUP-210", "商用主供", "1")));
    SupplierRatioShapeResolver resolver =
        new SupplierRatioShapeResolverImpl(mapper);

    SupplierRatioResolution result =
        resolver.resolve(policyResolution("COMMERCIAL", "201850113", "2026-08"));

    assertThat(result.blocked()).isFalse();
    verify(mapper).selectList(any(Wrapper.class));
    verifyNoMoreInteractions(mapper);
  }
}
