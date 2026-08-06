package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import com.sanhua.marketingcost.dto.BomPartWhereUsedItemResponse;
import com.sanhua.marketingcost.service.BomPartWhereUsedService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

@DisplayName("BomPartWhereUsedController")
class BomPartWhereUsedControllerTest {
  private BomPartWhereUsedService service;
  private BomPartWhereUsedController controller;

  @BeforeEach
  void setUp() {
    service = mock(BomPartWhereUsedService.class);
    controller = new BomPartWhereUsedController(service);
  }

  @Test
  @DisplayName("分页参数透传并返回统一分页结构")
  void pageDelegatesToService() {
    when(service.page("PLATE", "301070074", "10539", 2, 20))
        .thenReturn(new PageResult<BomPartWhereUsedItemResponse>(List.of(), 8L));

    var result = controller.page("PLATE", "301070074", "10539", 2, 20);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getTotal()).isEqualTo(8);
    verify(service).page("PLATE", "301070074", "10539", 2, 20);
  }

  @Test
  @DisplayName("接口使用独立的物料使用查询权限")
  void usesDedicatedPermission() throws Exception {
    Method method = BomPartWhereUsedController.class.getMethod(
        "page",
        String.class,
        String.class,
        String.class,
        Integer.class,
        Integer.class);

    assertThat(method.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('base:u9-material-usage:list')");
  }
}
