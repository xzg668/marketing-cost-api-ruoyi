package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import com.sanhua.marketingcost.entity.PriceScrap;
import com.sanhua.marketingcost.service.PriceScrapService;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class PriceScrapControllerTest {

  @Test
  @DisplayName("T5 /price-scrap/items/current：按 CMS 回收料号查询当前价")
  void currentReturnsCurrentScrapPrice() {
    PriceScrapService service = mock(PriceScrapService.class);
    PriceScrapController controller = new PriceScrapController(service);
    PriceScrap row = new PriceScrap();
    row.setScrapCode("301990317");
    row.setRecyclePrice(new BigDecimal("80.12"));
    when(service.getCurrentByScrapCode("301990317")).thenReturn(row);

    var result = controller.current("301990317");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getScrapCode()).isEqualTo("301990317");
    verify(service).getCurrentByScrapCode("301990317");
  }

  @Test
  @DisplayName("T5 /price-scrap/items/current：查不到当前价返回 BAD_REQUEST")
  void currentReturnsBadRequestWhenMissing() {
    PriceScrapService service = mock(PriceScrapService.class);
    PriceScrapController controller = new PriceScrapController(service);

    var result = controller.current("301990317");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
  }

  @Test
  @DisplayName("T5 Controller 权限：当前价查询使用废料管理查询权限")
  void currentPermission() throws Exception {
    Method method = PriceScrapController.class.getMethod("current", String.class);

    PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.value()).isEqualTo("@ss.hasPermi('price:scrap:list')");
  }
}
