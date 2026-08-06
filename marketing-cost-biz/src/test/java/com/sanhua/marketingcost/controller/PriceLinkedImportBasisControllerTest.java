package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.exception.enums.GlobalErrorCodeConstants;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceLinkedImportBasisResponse;
import com.sanhua.marketingcost.service.PriceLinkedImportBasisService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;

@DisplayName("PLI2-09 查看联动价导入依据接口")
class PriceLinkedImportBasisControllerTest {

  @Test
  @DisplayName("GET接口返回Service保存的导入依据")
  void returnsImportBasis() {
    PriceLinkedImportBasisService service = mock(PriceLinkedImportBasisService.class);
    PriceLinkedImportBasisResponse response = new PriceLinkedImportBasisResponse();
    response.setLinkedItemId(901L);
    response.setImportBasisAvailable(true);
    when(service.getImportBasis(901L)).thenReturn(response);
    PriceLinkedImportBasisController controller =
        new PriceLinkedImportBasisController(service);

    CommonResult<PriceLinkedImportBasisResponse> result =
        controller.getImportBasis(901L);

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getLinkedItemId()).isEqualTo(901L);
    verify(service).getImportBasis(901L);
  }

  @Test
  @DisplayName("记录不存在或跨业务单元时统一返回BAD_REQUEST")
  void missingOrInaccessibleReturnsBadRequest() {
    PriceLinkedImportBasisService service = mock(PriceLinkedImportBasisService.class);
    when(service.getImportBasis(902L)).thenReturn(null);
    PriceLinkedImportBasisController controller =
        new PriceLinkedImportBasisController(service);

    CommonResult<PriceLinkedImportBasisResponse> result =
        controller.getImportBasis(902L);

    assertThat(result.getCode()).isEqualTo(GlobalErrorCodeConstants.BAD_REQUEST.getCode());
    assertThat(result.getMsg()).contains("not found or not accessible");
  }

  @Test
  @DisplayName("接口路径固定且必须拥有联动价查看权限")
  void endpointRequiresListPermission() throws Exception {
    Method method = PriceLinkedImportBasisController.class.getMethod(
        "getImportBasis", Long.class);

    GetMapping mapping = method.getAnnotation(GetMapping.class);
    PreAuthorize authorize = method.getAnnotation(PreAuthorize.class);
    assertThat(mapping.value()).containsExactly("/items/{id}/import-basis");
    assertThat(authorize.value()).isEqualTo("@ss.hasPermi('price:linked-item:list')");
  }
}
