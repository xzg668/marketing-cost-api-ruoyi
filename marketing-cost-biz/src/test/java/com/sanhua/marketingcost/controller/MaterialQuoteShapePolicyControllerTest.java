package com.sanhua.marketingcost.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyEnabledRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyQuery;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import com.sanhua.marketingcost.service.MaterialQuoteShapePolicyService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class MaterialQuoteShapePolicyControllerTest {

  private MaterialQuoteShapePolicyService service;
  private MaterialQuoteShapePolicyController controller;

  @BeforeEach
  void setUp() {
    service = mock(MaterialQuoteShapePolicyService.class);
    controller = new MaterialQuoteShapePolicyController(service);
  }

  @Test
  @DisplayName("列表和详情接口返回规则服务结果")
  void listAndGet() {
    MaterialQuoteShapePolicyResponse response = response(1L, "PURCHASE", 1);
    when(service.list(any(MaterialQuoteShapePolicyQuery.class))).thenReturn(List.of(response));
    when(service.get(1L)).thenReturn(response);

    CommonResult<List<MaterialQuoteShapePolicyResponse>> list =
        controller.list(
            "商用", "201850", "烧结", "规格A", "型号B", "FIXED", 1, "2026-08");
    CommonResult<MaterialQuoteShapePolicyResponse> detail = controller.get(1L);

    assertThat(list.isSuccess()).isTrue();
    assertThat(list.getData()).containsExactly(response);
    assertThat(detail.getData()).isSameAs(response);
  }

  @Test
  @DisplayName("新增、修改、启停、删除接口透传规则服务")
  void mutations() {
    MaterialQuoteShapePolicyRequest request = new MaterialQuoteShapePolicyRequest();
    MaterialQuoteShapePolicyEnabledRequest enabledRequest =
        new MaterialQuoteShapePolicyEnabledRequest();
    enabledRequest.setEnabled(0);
    MaterialQuoteShapePolicyResponse before = response(1L, "MANUFACTURE", 1);
    MaterialQuoteShapePolicyResponse after = response(1L, "PURCHASE", 1);
    MaterialQuoteShapePolicyResponse disabled = response(1L, "PURCHASE", 0);
    when(service.create(request)).thenReturn(after);
    when(service.get(1L)).thenReturn(before);
    when(service.update(1L, request)).thenReturn(after);
    when(service.setEnabled(1L, 0)).thenReturn(disabled);
    when(service.delete(1L)).thenReturn(true);

    assertThat(controller.create(request).getData()).isSameAs(after);
    assertThat(controller.update(1L, request).getData()).isSameAs(after);
    assertThat(controller.setEnabled(1L, enabledRequest).getData()).isSameAs(disabled);
    assertThat(controller.delete(1L).getData()).isTrue();

    verify(service).create(request);
    verify(service).update(1L, request);
    verify(service).setEnabled(1L, 0);
    verify(service).delete(1L);
  }

  @Test
  @DisplayName("查看、编辑、启停权限分离，所有写接口开启操作日志")
  void permissionAndAuditAnnotations() throws Exception {
    Method list =
        MaterialQuoteShapePolicyController.class.getMethod(
            "list",
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            Integer.class,
            String.class);
    Method create =
        MaterialQuoteShapePolicyController.class.getMethod(
            "create", MaterialQuoteShapePolicyRequest.class);
    Method update =
        MaterialQuoteShapePolicyController.class.getMethod(
            "update", Long.class, MaterialQuoteShapePolicyRequest.class);
    Method toggle =
        MaterialQuoteShapePolicyController.class.getMethod(
            "setEnabled", Long.class, MaterialQuoteShapePolicyEnabledRequest.class);
    Method delete =
        MaterialQuoteShapePolicyController.class.getMethod("delete", Long.class);

    assertThat(list.getAnnotation(PreAuthorize.class).value())
        .contains("bom-data:material-shape-policy:list");
    assertThat(create.getAnnotation(PreAuthorize.class).value())
        .contains("bom-data:material-shape-policy:edit");
    assertThat(update.getAnnotation(PreAuthorize.class).value())
        .contains("bom-data:material-shape-policy:edit");
    assertThat(delete.getAnnotation(PreAuthorize.class).value())
        .contains("bom-data:material-shape-policy:edit");
    assertThat(toggle.getAnnotation(PreAuthorize.class).value())
        .contains("bom-data:material-shape-policy:toggle");
    assertThat(create.getAnnotation(OperationLog.class).recordDiff()).isTrue();
    assertThat(update.getAnnotation(OperationLog.class).recordDiff()).isTrue();
    assertThat(toggle.getAnnotation(OperationLog.class).recordDiff()).isTrue();
    assertThat(delete.getAnnotation(OperationLog.class).recordDiff()).isTrue();
  }

  static MaterialQuoteShapePolicyResponse response(Long id, String shape, Integer enabled) {
    MaterialQuoteShapePolicyResponse response = new MaterialQuoteShapePolicyResponse();
    response.setId(id);
    response.setMaterialOrgCode("COMMERCIAL");
    response.setMaterialCode("201850113");
    response.setPolicyMode("FIXED");
    response.setFixedTargetShape(shape);
    response.setEffectiveFromMonth("2026-08");
    response.setEnabled(enabled);
    return response;
  }
}
