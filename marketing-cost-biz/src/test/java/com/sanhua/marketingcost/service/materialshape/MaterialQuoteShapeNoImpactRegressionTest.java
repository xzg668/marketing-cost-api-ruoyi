package com.sanhua.marketingcost.service.materialshape;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.entity.MaterialQuoteShapePolicy;
import com.sanhua.marketingcost.enums.QuoteMaterialShape;
import com.sanhua.marketingcost.mapper.MaterialQuoteShapePolicyMapper;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MaterialQuoteShapeNoImpactRegressionTest {

  @Test
  @DisplayName("无规则普通节点只做标准化，不写规则、最终节点或历史表")
  void noPolicyResolutionIsReadOnly() {
    MaterialQuoteShapePolicyMapper mapper =
        mock(MaterialQuoteShapePolicyMapper.class);
    when(mapper.selectList(any())).thenReturn(List.of());
    MaterialQuoteShapeResolver resolver =
        new MaterialQuoteShapeResolverImpl(
            mapper, new ShapePolicyFingerprint(new ObjectMapper()));

    MaterialQuoteShapeResolution result =
        resolver.resolve(
            new MaterialQuoteShapeRequest(
                "COMMERCIAL", "NORMAL-1", "2026-08", "采购件"));

    assertThat(result.effectiveShape()).isEqualTo(QuoteMaterialShape.PURCHASE);
    assertThat(result.source()).isEqualTo(MaterialQuoteShapeSource.U9);
    verify(mapper).selectList(any());
    verify(mapper, never()).insert(any(MaterialQuoteShapePolicy.class));
    verify(mapper, never()).updateById(any(MaterialQuoteShapePolicy.class));
    verify(mapper, never()).deleteById(any(Long.class));
  }

  @Test
  @DisplayName("解析器生产依赖不包含最终 BOM、确认、成本或 U9 写入 Mapper")
  void resolverHasNoHistoricalMutationDependency() {
    assertThat(MaterialQuoteShapeResolverImpl.class.getDeclaredFields())
        .extracting(Field::getType)
        .allMatch(
            type ->
                !type.getSimpleName().contains("QuoteEffectiveBom")
                    && !type.getSimpleName().contains("Confirmation")
                    && !type.getSimpleName().contains("CostRun")
                    && !type.getSimpleName().contains("MaterialMasterMapper")
                    && !type.getSimpleName().contains("BomRaw"));
  }
}
