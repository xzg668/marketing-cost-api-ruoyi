package com.sanhua.marketingcost.aspect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.controller.MaterialQuoteShapePolicyController;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyRequest;
import com.sanhua.marketingcost.dto.materialshape.MaterialQuoteShapePolicyResponse;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.service.MaterialQuoteShapePolicyService;
import java.lang.reflect.Method;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MaterialQuoteShapePolicyAuditTest {

  @AfterEach
  void clearContext() {
    OperationLogDiffContext.clear();
  }

  @Test
  @DisplayName("规则修改日志记录真实修改前和修改后内容，并在完成后清理线程上下文")
  void updateRecordsRealBeforeAndAfter() throws Throwable {
    MaterialQuoteShapePolicyService service = mock(MaterialQuoteShapePolicyService.class);
    MaterialQuoteShapePolicyController controller =
        new MaterialQuoteShapePolicyController(service);
    MaterialQuoteShapePolicyResponse before = response("MANUFACTURE");
    MaterialQuoteShapePolicyResponse after = response("PURCHASE");
    MaterialQuoteShapePolicyRequest request = new MaterialQuoteShapePolicyRequest();
    request.setFixedTargetShape("PURCHASE");
    when(service.get(1L)).thenReturn(before);
    when(service.update(1L, request)).thenReturn(after);

    Method method =
        MaterialQuoteShapePolicyController.class.getMethod(
            "update", Long.class, MaterialQuoteShapePolicyRequest.class);
    OperationLog annotation = method.getAnnotation(OperationLog.class);
    ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
    MethodSignature signature = mock(MethodSignature.class);
    when(joinPoint.getSignature()).thenReturn(signature);
    when(signature.getMethod()).thenReturn(method);
    when(signature.getDeclaringTypeName())
        .thenReturn(MaterialQuoteShapePolicyController.class.getName());
    when(signature.getName()).thenReturn("update");
    when(signature.getParameterNames()).thenReturn(new String[] {"id", "request"});
    when(joinPoint.getArgs()).thenReturn(new Object[] {1L, request});
    when(joinPoint.proceed()).thenAnswer(invocation -> controller.update(1L, request));
    SysOperationLogMapper logMapper = mock(SysOperationLogMapper.class);
    OperationLogAspect aspect = new OperationLogAspect(logMapper, new ObjectMapper());

    aspect.around(joinPoint, annotation);

    ArgumentCaptor<SysOperationLog> captor =
        ArgumentCaptor.forClass(SysOperationLog.class);
    verify(logMapper).insert(captor.capture());
    SysOperationLog log = captor.getValue();
    assertThat(log.getTitle()).isEqualTo("料品形态规则");
    assertThat(log.getTargetId()).isEqualTo("1");
    assertThat(log.getBeforeData())
        .contains("MANUFACTURE")
        .doesNotContain("PURCHASE");
    assertThat(log.getAfterData())
        .contains("PURCHASE")
        .doesNotContain("MANUFACTURE");
    assertThat(OperationLogDiffContext.current()).isNull();
  }

  private static MaterialQuoteShapePolicyResponse response(String shape) {
    MaterialQuoteShapePolicyResponse response =
        new MaterialQuoteShapePolicyResponse();
    response.setId(1L);
    response.setMaterialOrgCode("COMMERCIAL");
    response.setMaterialCode("201850113");
    response.setPolicyMode("FIXED");
    response.setFixedTargetShape(shape);
    response.setEffectiveFromMonth("2026-08");
    response.setEnabled(1);
    return response;
  }
}
