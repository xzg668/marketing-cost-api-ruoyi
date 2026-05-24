package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.MakePartPriceCalcPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceCalcQueryRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGapPageResponse;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateRequest;
import com.sanhua.marketingcost.dto.MakePartPriceGenerateResponse;
import com.sanhua.marketingcost.entity.MakePartPriceCalcRow;
import com.sanhua.marketingcost.service.MakePartPriceCalcService;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

class MakePartPriceCalcControllerTest {

  private MakePartPriceCalcService service;
  private MakePartPriceCalcController controller;

  @BeforeEach
  void setUp() {
    service = mock(MakePartPriceCalcService.class);
    controller = new MakePartPriceCalcController(service);
  }

  @Test
  @DisplayName("分页查询透传条件")
  void pageDelegatesToService() {
    MakePartPriceCalcQueryRequest request = new MakePartPriceCalcQueryRequest();
    request.setOnlyError(true);
    when(service.page(request)).thenReturn(new MakePartPriceCalcPageResponse(0, List.of()));

    CommonResult<MakePartPriceCalcPageResponse> result = controller.page(request);

    assertThat(result.isSuccess()).isTrue();
    verify(service).page(request);
  }

  @Test
  @DisplayName("缺价清单分页查询透传条件")
  void gapPageDelegatesToService() {
    MakePartPriceCalcQueryRequest request = new MakePartPriceCalcQueryRequest();
    request.setCalcBatchId("MPPG-1");
    request.setMissingPriceRole("RAW");
    when(service.gapPage(request)).thenReturn(new MakePartPriceGapPageResponse(0, List.of()));

    CommonResult<MakePartPriceGapPageResponse> result = controller.gapPage(request);

    assertThat(result.isSuccess()).isTrue();
    verify(service).gapPage(request);
  }

  @Test
  @DisplayName("生成接口返回批次统计")
  void generateReturnsBatchStats() {
    MakePartPriceGenerateRequest request = new MakePartPriceGenerateRequest();
    MakePartPriceGenerateResponse response =
        new MakePartPriceGenerateResponse("MPPG-1", 1, 2, 2, 0, 0);
    when(service.generate(request)).thenReturn(response);

    CommonResult<MakePartPriceGenerateResponse> result = controller.generate(request);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getCalcBatchId()).isEqualTo("MPPG-1");
  }

  @Test
  @DisplayName("详情能看到计算追溯")
  void detailReturnsTraceRemark() {
    MakePartPriceCalcRow row = new MakePartPriceCalcRow();
    row.setRemark("计算追溯: gross_weight_g=80");
    when(service.get(8L)).thenReturn(row);

    CommonResult<MakePartPriceCalcRow> result = controller.detail(8L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getRemark()).contains("计算追溯");
  }

  @Test
  @DisplayName("最新批次透传查询条件")
  void latestBatchDelegates() {
    when(service.latestBatch("OA-001", "COMMERCIAL", "MAKE-001")).thenReturn("MPPG-latest");

    CommonResult<String> result = controller.latestBatch("OA-001", "COMMERCIAL", "MAKE-001");

    assertThat(result.getData()).isEqualTo("MPPG-latest");
  }

  @Test
  @DisplayName("状态汇总透传查询条件")
  void statusSummaryDelegates() {
    MakePartPriceCalcQueryRequest request = new MakePartPriceCalcQueryRequest();
    when(service.statusSummary(request)).thenReturn(Map.of("OK", 2));

    CommonResult<Map<String, Integer>> result = controller.statusSummary(request);

    assertThat(result.getData()).containsEntry("OK", 2);
  }

  @Test
  @DisplayName("路由：详情接口只匹配数字 id，避免 page/gap-page/status-summary 被当成 id")
  void detailRouteOnlyMatchesNumericId() throws Exception {
    Method detailMethod = MakePartPriceCalcController.class.getMethod("detail", Long.class);

    assertThat(detailMethod.getAnnotation(GetMapping.class).value())
        .containsExactly("/{id:\\d+}");
  }

  @Test
  @DisplayName("导出设置 xlsx 响应头并调用服务")
  void exportSetsHeaders() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    when(service.export(any(MakePartPriceCalcQueryRequest.class), any(OutputStream.class)))
        .thenReturn(1);

    controller.export(new MakePartPriceCalcQueryRequest(), response);

    assertThat(response.getContentType())
        .contains("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    assertThat(response.getHeader("Content-Disposition")).contains("filename*=UTF-8''");
    verify(service).export(any(MakePartPriceCalcQueryRequest.class), eq(response.getOutputStream()));
  }

  @Test
  @DisplayName("权限：查询、生成、导出使用制造件价格生成权限码")
  void permissions() throws Exception {
    assertPerm("page", "@ss.hasPermi('price:make-part-calc:list')", MakePartPriceCalcQueryRequest.class);
    assertPerm("gapPage", "@ss.hasPermi('price:make-part-calc:list')", MakePartPriceCalcQueryRequest.class);
    assertPerm("generate", "@ss.hasPermi('price:make-part-calc:generate')", MakePartPriceGenerateRequest.class);
    Method exportMethod = MakePartPriceCalcController.class.getMethod(
        "export", MakePartPriceCalcQueryRequest.class, jakarta.servlet.http.HttpServletResponse.class);
    assertThat(exportMethod.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('price:make-part-calc:export')");
  }

  private void assertPerm(String methodName, String expected, Class<?>... parameterTypes)
      throws Exception {
    Method method = MakePartPriceCalcController.class.getMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
  }
}
