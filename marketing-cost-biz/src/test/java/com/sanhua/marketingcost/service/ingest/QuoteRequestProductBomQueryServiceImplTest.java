package com.sanhua.marketingcost.service.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.ingest.QuoteRequestProductBomListItemResponse;
import com.sanhua.marketingcost.mapper.QuoteRequestProductBomMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteRequestProductBomQueryServiceImplTest {
  private QuoteRequestProductBomMapper mapper;
  private QuoteRequestProductBomQueryServiceImpl service;

  @BeforeEach
  void setUp() {
    mapper = mock(QuoteRequestProductBomMapper.class);
    service = new QuoteRequestProductBomQueryServiceImpl(mapper);
  }

  @Test
  void pageProductBomRowsNormalizesPaginationAndEnrichesStatus() {
    QuoteRequestProductBomListItemResponse row = new QuoteRequestProductBomListItemResponse();
    row.setOaFormItemId(100L);
    row.setTaskId(501L);
    row.setBomStatus("REUSED_CURRENT_MONTH");
    when(
            mapper.countProductBomRows(
                "OA",
                "MAT",
                "客户",
                "BARE",
                "BOX",
                "COMMERCIAL",
                "张三",
                true,
                "APPROVED",
                List.of("REUSED_CURRENT_MONTH")))
        .thenReturn(1L);
    when(
            mapper.selectProductBomRows(
                "OA",
                "MAT",
                "客户",
                "BARE",
                "BOX",
                "COMMERCIAL",
                "张三",
                true,
                "APPROVED",
                List.of("REUSED_CURRENT_MONTH"),
                50,
                50))
        .thenReturn(List.of(row));

    PageResult<QuoteRequestProductBomListItemResponse> page =
        service.pageProductBomRows(
            2,
            50,
            " OA ",
            " MAT ",
            " 客户 ",
            " BARE ",
            " BOX ",
            " COMMERCIAL ",
            " 张三 ",
            true,
            " APPROVED ",
            List.of("REUSED_CURRENT_MONTH"));

    assertThat(page.getTotal()).isEqualTo(1);
    assertThat(page.getList().get(0).getBomStatusLabel()).isEqualTo("已沿用");
    assertThat(page.getList().get(0).getCanCostRun()).isTrue();
    assertThat(page.getList().get(0).getOaTodoPushStatus()).isEqualTo("NOT_PUSHED");
    verify(mapper)
        .selectProductBomRows(
            "OA",
            "MAT",
            "客户",
            "BARE",
            "BOX",
            "COMMERCIAL",
            "张三",
            true,
            "APPROVED",
            List.of("REUSED_CURRENT_MONTH"),
            50,
            50);
  }

  @Test
  void emptyStatusDefaultsToNotCheckedAndUsesMaxPageSize() {
    QuoteRequestProductBomListItemResponse row = new QuoteRequestProductBomListItemResponse();
    row.setOaFormItemId(101L);
    when(mapper.countProductBomRows(null, null, null, null, null, null, null, null, null, List.of())).thenReturn(1L);
    when(mapper.selectProductBomRows(null, null, null, null, null, null, null, null, null, List.of(), 200, 0))
        .thenReturn(List.of(row));

    PageResult<QuoteRequestProductBomListItemResponse> page =
        service.pageProductBomRows(0, 1000, null, null, null, null, null, null, null, null, null, Arrays.asList("", null));

    assertThat(page.getList().get(0).getBomStatus()).isEqualTo("NOT_CHECKED");
    assertThat(page.getList().get(0).getBomStatusLabel()).isEqualTo("未检查");
    assertThat(page.getList().get(0).getCanCostRun()).isFalse();
    verify(mapper).selectProductBomRows(null, null, null, null, null, null, null, null, null, List.of(), 200, 0);
  }

  @Test
  void legacySyncedRowsWithoutCostPeriodStillRenderAsCostReady() {
    QuoteRequestProductBomListItemResponse row = new QuoteRequestProductBomListItemResponse();
    row.setOaFormItemId(102L);
    row.setBomStatus("SYNCED");
    when(mapper.countProductBomRows(null, null, null, null, null, null, null, null, null, List.of())).thenReturn(1L);
    when(mapper.selectProductBomRows(null, null, null, null, null, null, null, null, null, List.of(), 20, 0))
        .thenReturn(List.of(row));

    PageResult<QuoteRequestProductBomListItemResponse> page =
        service.pageProductBomRows(1, 20, null, null, null, null, null, null, null, null, null, null);

    assertThat(page.getList().get(0).getBomStatusLabel()).isEqualTo("已同步");
    assertThat(page.getList().get(0).getCanCostRun()).isTrue();
  }
}
