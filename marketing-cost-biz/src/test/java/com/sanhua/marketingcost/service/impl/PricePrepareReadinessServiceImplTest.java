package com.sanhua.marketingcost.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryPageResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareTopProductSummaryResponse;
import com.sanhua.marketingcost.dto.priceprepare.PricePrepareReadinessResult;
import com.sanhua.marketingcost.entity.PricePrepareGap;
import com.sanhua.marketingcost.entity.PricePrepareItem;
import com.sanhua.marketingcost.mapper.PricePrepareGapMapper;
import com.sanhua.marketingcost.mapper.PricePrepareItemMapper;
import com.sanhua.marketingcost.service.PricePrepareQueryService;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PricePrepareReadinessServiceImplTest {

  private PricePrepareItemMapper itemMapper;
  private PricePrepareGapMapper gapMapper;
  private PricePrepareQueryService queryService;
  private PricePrepareReadinessServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    MapperBuilderAssistant assistant =
        new MapperBuilderAssistant(new MybatisConfiguration(), "");
    TableInfoHelper.initTableInfo(assistant, PricePrepareItem.class);
    TableInfoHelper.initTableInfo(assistant, PricePrepareGap.class);
  }

  @BeforeEach
  void setUp() {
    itemMapper = mock(PricePrepareItemMapper.class);
    gapMapper = mock(PricePrepareGapMapper.class);
    queryService = mock(PricePrepareQueryService.class);
    service = new PricePrepareReadinessServiceImpl(itemMapper, gapMapper, queryService);
  }

  @Test
  @DisplayName("当前结果全部就绪：返回 READY 并允许继续")
  void readyWhenCurrentItemsReady() {
    when(queryService.pageTopProductSummaries(any()))
        .thenReturn(topPage(topSummary("OA-001", "TOP-1", 3, 3, 0, "READY")));

    PricePrepareReadinessResult result = service.check(" OA-001 ", " 2026-05 ");

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.isAllowContinue()).isTrue();
    assertThat(result.isWarning()).isFalse();
    assertThat(result.getPrepareNo()).isNull();
    assertThat(result.getMessage()).isEqualTo("价格准备已完成");
  }

  @Test
  @DisplayName("未准备：当前阶段 warning 并允许继续")
  void warningWhenNoCurrentResult() {
    when(queryService.pageTopProductSummaries(any())).thenReturn(topPage());

    PricePrepareReadinessResult result = service.check("OA-001", "2026-05");

    assertThat(result.getStatus()).isEqualTo("NOT_PREPARED");
    assertThat(result.isAllowContinue()).isTrue();
    assertThat(result.isWarning()).isTrue();
    assertThat(result.getMessage()).contains("尚未执行价格准备", "实时成本将继续");
  }

  @Test
  @DisplayName("有缺口：返回当前缺口数量和摘要并允许继续")
  void warningWhenCurrentGapsExist() {
    PricePrepareGap gap = new PricePrepareGap();
    gap.setGapMaterialCode("MAT-GAP");
    gap.setTopProductCode("TOP-1");
    gap.setMessage("缺少包装子件价格");
    when(queryService.pageTopProductSummaries(any()))
        .thenReturn(topPage(topSummary("OA-001", "TOP-1", 3, 2, 2, "PARTIAL")));
    when(gapMapper.selectList(any())).thenReturn(List.of(gap));

    PricePrepareReadinessResult result = service.check("OA-001", "2026-05");

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.isAllowContinue()).isTrue();
    assertThat(result.getGapCount()).isEqualTo(2);
    assertThat(result.getGapSummaries()).containsExactly("TOP-1: 缺少包装子件价格");
    assertThat(result.getMessage()).contains("共 1 个顶级产品", "TOP-1");
    verify(gapMapper).selectList(any(LambdaQueryWrapper.class));
  }

  @Test
  @DisplayName("无缺口但存在未就绪顶级产品：返回 PARTIAL 并允许继续")
  void warningWhenItemsNotReadyWithoutGapRows() {
    when(queryService.pageTopProductSummaries(any()))
        .thenReturn(topPage(topSummary("OA-001", "TOP-1", 3, 2, 0, "PARTIAL")));
    when(gapMapper.selectList(any())).thenReturn(List.of());

    PricePrepareReadinessResult result = service.check("OA-001", "2026-05");

    assertThat(result.getStatus()).isEqualTo("PARTIAL");
    assertThat(result.isAllowContinue()).isTrue();
    assertThat(result.getMessage()).contains("1 个未完成", "实时成本将继续");
  }

  @Test
  @DisplayName("正式阻断开关打开：未准备时阻断")
  void blocksWhenStrictModeEnabled() {
    service.setBlockOnNotReady(true);
    when(queryService.pageTopProductSummaries(any())).thenReturn(topPage());

    PricePrepareReadinessResult result = service.check("OA-001", "2026-05");

    assertThat(result.getStatus()).isEqualTo("NOT_PREPARED");
    assertThat(result.isAllowContinue()).isFalse();
    assertThat(result.isBlocking()).isTrue();
    assertThat(result.getMessage()).contains("已阻断实时成本");
  }

  @Test
  @DisplayName("产品行维度检查：同 OA 相同成品料号只读取当前 oaFormItemId")
  void checkQuoteItemScopeIgnoresOtherSameProductRows() {
    PricePrepareItem item = new PricePrepareItem();
    item.setPrepareNo("PPR-B");
    item.setOaNo("OA-001");
    item.setOaFormItemId(202L);
    item.setTopProductCode("TOP-SAME");
    item.setPeriodMonth("2026-05");
    item.setStatus("READY");
    when(itemMapper.selectList(any())).thenReturn(List.of(item));
    when(gapMapper.selectList(any())).thenReturn(List.of());

    PricePrepareReadinessResult result =
        service.check("OA-001", 202L, "TOP-SAME", "2026-05");

    assertThat(result.getStatus()).isEqualTo("READY");
    assertThat(result.getPrepareNo()).isEqualTo("PPR-B");
    ArgumentCaptor<LambdaQueryWrapper<PricePrepareItem>> itemQueryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    ArgumentCaptor<LambdaQueryWrapper<PricePrepareGap>> gapQueryCaptor =
        ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    verify(itemMapper).selectList(itemQueryCaptor.capture());
    verify(gapMapper).selectList(gapQueryCaptor.capture());
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                    itemQueryCaptor.getValue())
                .getSqlSegment())
        .contains("oa_form_item_id", "top_product_code");
    assertThat(itemQueryCaptor.getValue().getParamNameValuePairs().values())
        .contains("OA-001", 202L, "TOP-SAME", "2026-05");
    assertThat(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>)
                    gapQueryCaptor.getValue())
                .getSqlSegment())
        .contains("oa_form_item_id", "top_product_code");
  }

  private PricePrepareTopProductSummaryPageResponse topPage(PricePrepareTopProductSummaryResponse... records) {
    return new PricePrepareTopProductSummaryPageResponse(records.length, List.of(records));
  }

  private PricePrepareTopProductSummaryResponse topSummary(
      String oaNo, String topProductCode, int total, int ready, int gap, String status) {
    PricePrepareTopProductSummaryResponse response = new PricePrepareTopProductSummaryResponse();
    response.setOaNo(oaNo);
    response.setTopProductCode(topProductCode);
    response.setTotalCount(total);
    response.setReadyCount(ready);
    response.setGapCount(gap);
    response.setStatus(status);
    return response;
  }
}
