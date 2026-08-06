package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceItemImportResponse;
import com.sanhua.marketingcost.dto.PriceLinkedImportCommand;
import com.sanhua.marketingcost.dto.PriceLinkedType2ImportPreviewResponse;
import com.sanhua.marketingcost.service.FactorMonthlyPriceAdjustmentService;
import com.sanhua.marketingcost.service.PriceLinkedImportDispatchService;
import com.sanhua.marketingcost.service.PriceLinkedItemService;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

@DisplayName("PLI2-10 类型2预检和确认Controller")
class PriceLinkedType2ImportControllerTest {

  @Test
  @DisplayName("预检接口透传文件和导入参数并返回SHA")
  void previewDelegatesToDispatchService() {
    Fixture fixture = new Fixture();
    PriceLinkedType2ImportPreviewResponse preview =
        new PriceLinkedType2ImportPreviewResponse();
    preview.setFileSha256("abc123");
    preview.setTemplateType("TYPE2");
    preview.setCanConfirm(true);
    when(fixture.dispatch.preview(any())).thenReturn(preview);

    CommonResult<PriceLinkedType2ImportPreviewResponse> result =
        fixture.controller.previewImportExcel(
            fixture.file(), "2026-07", "COMMERCIAL", false,
            "APPEND_ONLY", "2026-07-01", "KEEP_EXISTING");

    assertThat(result.isSuccess()).isTrue();
    assertThat(result.getData().getFileSha256()).isEqualTo("abc123");
    ArgumentCaptor<PriceLinkedImportCommand> captor =
        ArgumentCaptor.forClass(PriceLinkedImportCommand.class);
    verify(fixture.dispatch).preview(captor.capture());
    assertThat(captor.getValue().getPricingMonth()).isEqualTo("2026-07");
    assertThat(captor.getValue().getBusinessUnitType()).isEqualTo("COMMERCIAL");
    assertThat(captor.getValue().getExpectedPreviewSha256()).isNull();
  }

  @Test
  @DisplayName("确认接口把预检SHA传给分流服务")
  void confirmPassesPreviewShaToDispatchService() {
    Fixture fixture = new Fixture();
    PriceItemImportResponse response = new PriceItemImportResponse();
    response.setTemplateType("TYPE2");
    response.setImportStatus("SUCCESS");
    when(fixture.dispatch.confirm(any())).thenReturn(response);

    CommonResult<PriceItemImportResponse> result = fixture.controller.importExcel(
        fixture.file(),
        "2026-07",
        "COMMERCIAL",
        false,
        "APPEND_ONLY",
        "2026-07-01",
        "KEEP_EXISTING",
        "sha-from-preview");

    assertThat(result.isSuccess()).isTrue();
    ArgumentCaptor<PriceLinkedImportCommand> captor =
        ArgumentCaptor.forClass(PriceLinkedImportCommand.class);
    verify(fixture.dispatch).confirm(captor.capture());
    assertThat(captor.getValue().getExpectedPreviewSha256())
        .isEqualTo("sha-from-preview");
  }

  @Test
  @DisplayName("分流服务拒绝文件替换时Controller返回BAD_REQUEST")
  void dispatchValidationFailureReturnsBadRequest() {
    Fixture fixture = new Fixture();
    when(fixture.dispatch.confirm(any()))
        .thenThrow(new IllegalArgumentException("文件SHA-256与预检不一致"));

    CommonResult<PriceItemImportResponse> result = fixture.controller.importExcel(
        fixture.file(),
        "2026-07",
        "COMMERCIAL",
        false,
        null,
        null,
        null,
        "wrong-sha");

    assertThat(result.isSuccess()).isFalse();
    assertThat(result.getMsg()).contains("SHA-256");
  }

  @Test
  @DisplayName("预检和确认接口均要求联动价导入权限")
  void bothEndpointsRequireImportPermission() throws Exception {
    Method preview = PriceLinkedItemController.class.getMethod(
        "previewImportExcel",
        org.springframework.web.multipart.MultipartFile.class,
        String.class,
        String.class,
        boolean.class,
        String.class,
        String.class,
        String.class);
    Method confirm = PriceLinkedItemController.class.getMethod(
        "importExcel",
        org.springframework.web.multipart.MultipartFile.class,
        String.class,
        String.class,
        boolean.class,
        String.class,
        String.class,
        String.class,
        String.class);

    assertThat(preview.getAnnotation(PostMapping.class).value())
        .containsExactly("/items/import-excel/preview");
    assertThat(preview.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('price:linked-item:import')");
    assertThat(confirm.getAnnotation(PreAuthorize.class).value())
        .isEqualTo("@ss.hasPermi('price:linked-item:import')");
  }

  private static final class Fixture {

    private final PriceLinkedItemService standard = mock(PriceLinkedItemService.class);
    private final FactorMonthlyPriceAdjustmentService adjustment =
        mock(FactorMonthlyPriceAdjustmentService.class);
    private final PriceLinkedImportDispatchService dispatch =
        mock(PriceLinkedImportDispatchService.class);
    private final PriceLinkedItemController controller =
        new PriceLinkedItemController(standard, adjustment, dispatch);

    private MockMultipartFile file() {
      return new MockMultipartFile(
          "file",
          "type2.xls",
          "application/vnd.ms-excel",
          new byte[]{1, 2, 3});
    }
  }
}
