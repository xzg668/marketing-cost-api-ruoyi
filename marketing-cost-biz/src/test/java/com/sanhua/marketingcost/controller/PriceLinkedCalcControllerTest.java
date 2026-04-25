package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedCalcTraceResponse;
import com.sanhua.marketingcost.service.PriceLinkedCalcService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PriceLinkedCalcController.trace 单测 —— T16 新增。
 *
 * <p>沿用项目里 Controller 单测惯例：new Controller + mock Service，不起 MockMvc。
 * 覆盖：
 * <ol>
 *   <li>id 存在：透传 Service 的 {@link PriceLinkedCalcTraceResponse} 进 {@code CommonResult.success}</li>
 *   <li>id 不存在（Service 返 null）：映射 {@code NOT_FOUND} 错误码，不抛 500</li>
 * </ol>
 */
class PriceLinkedCalcControllerTest {

  private PriceLinkedCalcService priceLinkedCalcService;
  private PriceLinkedCalcController controller;

  @BeforeEach
  void setUp() {
    priceLinkedCalcService = mock(PriceLinkedCalcService.class);
    controller = new PriceLinkedCalcController(priceLinkedCalcService);
  }

  @Test
  @DisplayName("/items/{id}/trace：Service 返 trace，Controller 包 success")
  void trace_existing_returnsSuccess() {
    String json = "{\"normalizedExpr\":\"[Cu]+[process_fee]\",\"result\":93.73}";
    when(priceLinkedCalcService.getTrace(42L))
        .thenReturn(new PriceLinkedCalcTraceResponse(42L, json));

    CommonResult<PriceLinkedCalcTraceResponse> result = controller.trace(42L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getId()).isEqualTo(42L);
    assertThat(result.getData().getTraceJson()).isEqualTo(json);
    verify(priceLinkedCalcService).getTrace(42L);
  }

  @Test
  @DisplayName("/items/{id}/trace：Service 返 null，Controller 映射 NOT_FOUND")
  void trace_notFound_returnsNotFound() {
    when(priceLinkedCalcService.getTrace(999L)).thenReturn(null);

    CommonResult<PriceLinkedCalcTraceResponse> result = controller.trace(999L);

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.NOT_FOUND.getCode());
    assertThat(result.getMsg()).contains("not found");
    assertThat(result.getData()).isNull();
  }
}
