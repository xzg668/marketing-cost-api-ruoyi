package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.dto.PriceVariableBindingDto;
import com.sanhua.marketingcost.dto.PriceVariableBindingImportResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingPendingResponse;
import com.sanhua.marketingcost.dto.PriceVariableBindingRequest;
import com.sanhua.marketingcost.service.PriceVariableBindingService;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link PriceVariableBindingController} 单测。
 *
 * <p>与其它控制器测试同风格：直接 new + mock Service，不起 MockMvc。
 * 权限注解由 {@code @PreAuthorize} 在集成环境生效；此处只验证控制器与 service 的布线正确。
 */
class PriceVariableBindingControllerTest {

  private PriceVariableBindingService service;
  private PriceVariableBindingController controller;

  @BeforeEach
  void setUp() {
    service = mock(PriceVariableBindingService.class);
    controller = new PriceVariableBindingController(service);
  }

  @Test
  @DisplayName("GET /bindings?linkedItemId=X → service.listByLinkedItem 透传")
  void list() {
    PriceVariableBindingDto dto = new PriceVariableBindingDto();
    dto.setId(1L);
    dto.setLinkedItemId(101L);
    dto.setTokenName("材料含税价格");
    dto.setFactorCode("Cu");
    dto.setFactorName("电解铜");
    when(service.listByLinkedItem(101L)).thenReturn(List.of(dto));

    CommonResult<List<PriceVariableBindingDto>> r = controller.list(101L);
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getData()).hasSize(1);
    assertThat(r.getData().get(0).getFactorName()).isEqualTo("电解铜");
    verify(service).listByLinkedItem(101L);
  }

  @Test
  @DisplayName("GET /bindings/history → service.getHistory 透传")
  void history() {
    when(service.getHistory(101L, "材料含税价格")).thenReturn(List.of());
    CommonResult<List<PriceVariableBindingDto>> r = controller.history(101L, "材料含税价格");
    assertThat(r.isSuccess()).isTrue();
    verify(service).getHistory(101L, "材料含税价格");
  }

  @Test
  @DisplayName("POST /bindings → service.save 并返回 id")
  void save() {
    when(service.save(any(PriceVariableBindingRequest.class))).thenReturn(77L);

    PriceVariableBindingRequest req = new PriceVariableBindingRequest();
    req.setLinkedItemId(101L);
    req.setTokenName("材料含税价格");
    req.setFactorCode("Cu");
    req.setEffectiveDate(LocalDate.of(2026, 4, 1));

    CommonResult<Long> r = controller.save(req);
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getData()).isEqualTo(77L);
    verify(service).save(req);
  }

  @Test
  @DisplayName("DELETE /bindings/{id} → service.softDelete")
  void remove() {
    CommonResult<Void> r = controller.remove(88L);
    assertThat(r.isSuccess()).isTrue();
    verify(service).softDelete(88L);
  }

  @Test
  @DisplayName("GET /bindings/pending → service.getPending 透传")
  void pending() {
    PriceVariableBindingPendingResponse resp = new PriceVariableBindingPendingResponse();
    resp.setTotal(3);
    when(service.getPending()).thenReturn(resp);

    CommonResult<PriceVariableBindingPendingResponse> r = controller.pending();
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getData().getTotal()).isEqualTo(3);
    verify(service).getPending();
  }

  @Test
  @DisplayName("POST /bindings/import → MultipartFile 转 InputStream 调 service.importCsv")
  void importCsv() throws Exception {
    PriceVariableBindingImportResponse resp = new PriceVariableBindingImportResponse();
    resp.setTotal(2);
    resp.setInserted(2);
    when(service.importCsv(any(InputStream.class))).thenReturn(resp);

    MultipartFile file = new MockMultipartFile(
        "file", "test.csv", "text/csv",
        "header\nrow1".getBytes(StandardCharsets.UTF_8));

    CommonResult<PriceVariableBindingImportResponse> r = controller.importCsv(file);
    assertThat(r.isSuccess()).isTrue();
    assertThat(r.getData().getInserted()).isEqualTo(2);
    verify(service).importCsv(any(InputStream.class));
  }

  @Test
  @DisplayName("POST /bindings/import 空文件 → IllegalArgumentException")
  void importEmpty() {
    MultipartFile empty = new MockMultipartFile(
        "file", "empty.csv", "text/csv", new byte[0]);
    assertThatThrownBy(() -> controller.importCsv(empty))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能为空");
  }
}
