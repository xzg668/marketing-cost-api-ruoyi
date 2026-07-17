package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceAdjustRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeRequest;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceInitializeResponse;
import com.sanhua.marketingcost.dto.financequote.FinanceQuoteBasePriceResponse;
import com.sanhua.marketingcost.service.FinanceQuoteBasePriceService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class FinanceQuoteBasePriceControllerTest {

  @Test
  @DisplayName("查询、初始化和调整分别透传专用服务")
  void delegatesAllEndpoints() {
    FinanceQuoteBasePriceService service = mock(FinanceQuoteBasePriceService.class);
    FinanceQuoteBasePriceController controller = new FinanceQuoteBasePriceController(service);
    FinanceQuoteBasePriceResponse row = response(1L, "2026-07", "90");
    when(service.list("2026-07", "2026-12")).thenReturn(List.of(row));
    FinanceQuoteBasePriceInitializeRequest initializeRequest =
        new FinanceQuoteBasePriceInitializeRequest(
            "2026-07", "2026-12", new BigDecimal("90000"));
    FinanceQuoteBasePriceInitializeResponse initialized =
        new FinanceQuoteBasePriceInitializeResponse(
            1, 0, List.of("2026-07"), List.of(), List.of(row));
    when(service.initialize(initializeRequest)).thenReturn(initialized);
    FinanceQuoteBasePriceAdjustRequest adjustRequest =
        new FinanceQuoteBasePriceAdjustRequest(new BigDecimal("95000"), "特殊调整");
    FinanceQuoteBasePriceResponse adjusted = response(1L, "2026-07", "95");
    when(service.adjust(1L, adjustRequest)).thenReturn(adjusted);

    assertThat(controller.list("2026-07", "2026-12").getData()).containsExactly(row);
    assertThat(controller.initialize(initializeRequest).getData()).isSameAs(initialized);
    assertThat(controller.adjust(1L, adjustRequest).getData()).isSameAs(adjusted);
    verify(service).list("2026-07", "2026-12");
    verify(service).initialize(initializeRequest);
    verify(service).adjust(1L, adjustRequest);
  }

  @Test
  @DisplayName("接口路径和查询/编辑权限严格使用FCQ-02约定")
  void endpointAndPermissionContract() throws Exception {
    RequestMapping mapping = FinanceQuoteBasePriceController.class
        .getAnnotation(RequestMapping.class);
    assertThat(mapping.value()).containsExactly("/api/v1/finance-quote-base-prices/cu");

    assertPermission("list", new Class<?>[]{String.class, String.class},
        "@ss.hasPermi('cost:finance-cu-base:query')");
    assertPermission("initialize",
        new Class<?>[]{FinanceQuoteBasePriceInitializeRequest.class},
        "@ss.hasPermi('cost:finance-cu-base:edit')");
    assertPermission("adjust",
        new Class<?>[]{Long.class, FinanceQuoteBasePriceAdjustRequest.class},
        "@ss.hasPermi('cost:finance-cu-base:edit')");
  }

  private void assertPermission(String methodName, Class<?>[] parameterTypes, String expected)
      throws Exception {
    Method method = FinanceQuoteBasePriceController.class
        .getDeclaredMethod(methodName, parameterTypes);
    assertThat(method.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
  }

  private FinanceQuoteBasePriceResponse response(Long id, String month, String pricePerKg) {
    BigDecimal kg = new BigDecimal(pricePerKg);
    return new FinanceQuoteBasePriceResponse(
        id, month, "Cu", "财务报价基准", kg, kg.multiply(new BigDecimal("1000")),
        "公斤", "COMMERCIAL", null, null, null, null);
  }
}
