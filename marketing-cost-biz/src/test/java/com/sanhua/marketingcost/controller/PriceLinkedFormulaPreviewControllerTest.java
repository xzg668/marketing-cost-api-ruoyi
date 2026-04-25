package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewRequest;
import com.sanhua.marketingcost.dto.PriceLinkedFormulaPreviewResponse;
import com.sanhua.marketingcost.service.PriceLinkedFormulaPreviewService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PriceLinkedFormulaPreviewController 单测 —— 与项目其它 Controller 单测一致，
 * 直接构造 Controller 并 mock Service，不起 MockMvc。覆盖：
 * <ol>
 *   <li>正常：Service 返回带 result 的响应，Controller 透传进 {@code CommonResult.success}</li>
 *   <li>缺 materialCode：响应里有 warnings（Service 行为由 impl 单测保证，这里只校验透传）</li>
 *   <li>公式语法错：响应里有 error，Controller 仍然是 success（不把业务异常转成 500）</li>
 * </ol>
 */
class PriceLinkedFormulaPreviewControllerTest {

  private PriceLinkedFormulaPreviewService previewService;
  private PriceLinkedFormulaPreviewController controller;

  @BeforeEach
  void setUp() {
    previewService = mock(PriceLinkedFormulaPreviewService.class);
    controller = new PriceLinkedFormulaPreviewController(previewService);
  }

  @Test
  @DisplayName("正常：Service 返回 result + variables，Controller 包 CommonResult.success")
  void preview_success() {
    PriceLinkedFormulaPreviewResponse payload = new PriceLinkedFormulaPreviewResponse();
    payload.setNormalizedExpr("[Cu]+[process_fee]");
    payload.setResult(new BigDecimal("93.73"));
    PriceLinkedFormulaPreviewResponse.VariableDetail d = new PriceLinkedFormulaPreviewResponse.VariableDetail();
    d.setCode("Cu");
    d.setValue(new BigDecimal("90"));
    d.setSource("FINANCE_FACTOR");
    payload.setVariables(List.of(d));
    when(previewService.preview(any())).thenReturn(payload);

    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu+加工费");
    req.setMaterialCode("TP2Y2");

    CommonResult<PriceLinkedFormulaPreviewResponse> result = controller.preview(req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getNormalizedExpr()).isEqualTo("[Cu]+[process_fee]");
    assertThat(result.getData().getResult()).isEqualByComparingTo("93.73");
    verify(previewService).preview(req);
  }

  @Test
  @DisplayName("缺 materialCode：响应带 warnings，依然 success（业务性状况不应让接口失败）")
  void preview_missingMaterialCode_returnsWarnings() {
    PriceLinkedFormulaPreviewResponse payload = new PriceLinkedFormulaPreviewResponse();
    payload.setNormalizedExpr("[Cu]*2");
    payload.setResult(new BigDecimal("176"));
    payload.setWarnings(List.of("未提供 materialCode 无法取部品上下文"));
    when(previewService.preview(any())).thenReturn(payload);

    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu*2");

    CommonResult<PriceLinkedFormulaPreviewResponse> result = controller.preview(req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getWarnings())
        .anyMatch(w -> w.contains("未提供 materialCode"));
  }

  @Test
  @DisplayName("公式语法错：Service 写 error 字段；Controller 仍回 success，不抛 500")
  void preview_syntaxError_successWithErrorField() {
    PriceLinkedFormulaPreviewResponse payload = new PriceLinkedFormulaPreviewResponse();
    payload.setError("公式语法错误: 括号不平衡（差值=1）");
    when(previewService.preview(any())).thenReturn(payload);

    PriceLinkedFormulaPreviewRequest req = new PriceLinkedFormulaPreviewRequest();
    req.setFormulaExpr("Cu+加工费)");

    CommonResult<PriceLinkedFormulaPreviewResponse> result = controller.preview(req);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getResult()).isNull();
    assertThat(result.getData().getError()).contains("公式语法错误");
  }
}
