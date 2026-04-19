package com.sanhua.marketingcost.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.annotation.OperationType;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 操作日志切面单元测试
 */
class OperationLogAspectTest {

    private SysOperationLogMapper logMapper;
    private OperationLogAspect aspect;

    @BeforeEach
    void setUp() {
        logMapper = mock(SysOperationLogMapper.class);
        aspect = new OperationLogAspect(logMapper, new ObjectMapper());

        // 模拟登录用户
        User userDetails = new User("admin", "pass", List.of());
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userDetails, null, List.of());
        Map<String, Object> details = new HashMap<>();
        details.put(BusinessUnitContext.KEY_BUSINESS_UNIT_TYPE, "COMMERCIAL");
        auth.setDetails(details);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ========== 测试辅助：创建 mock 的 ProceedingJoinPoint ==========

    /** 带 @OperationLog 的示例方法 */
    @OperationLog(module = "测试模块", operationType = OperationType.UPDATE, targetIdParam = "id")
    public String sampleMethod(Long id, String data) {
        return "ok";
    }

    /** 带 recordDiff 的示例方法 */
    @OperationLog(module = "差异模块", operationType = OperationType.UPDATE, recordDiff = true, targetIdParam = "id")
    public String diffMethod(Long id, String data) {
        return "ok";
    }

    /** 会抛异常的示例方法 */
    @OperationLog(module = "异常模块", operationType = OperationType.DELETE)
    public void errorMethod() {
        throw new RuntimeException("测试异常");
    }

    private ProceedingJoinPoint mockJoinPoint(String methodName, Class<?>[] paramTypes,
                                               String[] paramNames, Object[] args,
                                               Object returnValue) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        MethodSignature signature = mock(MethodSignature.class);
        Method method = this.getClass().getMethod(methodName, paramTypes);

        when(joinPoint.getSignature()).thenReturn(signature);
        when(signature.getMethod()).thenReturn(method);
        when(signature.getDeclaringTypeName()).thenReturn(this.getClass().getName());
        when(signature.getName()).thenReturn(methodName);
        // 显式指定参数名，避免编译时没有 -parameters 选项导致反射拿不到真实参数名
        when(signature.getParameterNames()).thenReturn(paramNames);
        when(joinPoint.getArgs()).thenReturn(args);
        if (returnValue != null) {
            when(joinPoint.proceed()).thenReturn(returnValue);
        }
        return joinPoint;
    }

    // ========== 测试用例 ==========

    @Test
    @DisplayName("正常操作 — 记录基础字段和 target_id")
    void normalOperation_recordsLog() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                "sampleMethod", new Class[]{Long.class, String.class},
                new String[]{"id", "data"}, new Object[]{42L, "test-data"}, "ok"
        );
        OperationLog annotation = this.getClass().getMethod("sampleMethod", Long.class, String.class)
                .getAnnotation(OperationLog.class);

        when(logMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        Object result = aspect.around(joinPoint, annotation);

        assertEquals("ok", result);
        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(logMapper).insert(captor.capture());

        SysOperationLog logRecord = captor.getValue();
        assertEquals("测试模块", logRecord.getTitle());
        assertEquals(2, logRecord.getBusinessType()); // UPDATE
        assertEquals("admin", logRecord.getOperName());
        assertEquals("COMMERCIAL", logRecord.getBusinessUnitType());
        assertEquals("42", logRecord.getTargetId());
        assertEquals(0, logRecord.getStatus());
        assertNotNull(logRecord.getOperTime());
        assertTrue(logRecord.getCostTime() >= 0);
    }

    @Test
    @DisplayName("recordDiff=true — after_data 有值")
    void recordDiff_afterDataPopulated() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                "diffMethod", new Class[]{Long.class, String.class},
                new String[]{"id", "data"}, new Object[]{1L, "new-value"}, "ok"
        );
        OperationLog annotation = this.getClass().getMethod("diffMethod", Long.class, String.class)
                .getAnnotation(OperationLog.class);

        when(logMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(logMapper).insert(captor.capture());

        SysOperationLog logRecord = captor.getValue();
        assertNotNull(logRecord.getAfterData(), "recordDiff=true 时 after_data 应有值");
        assertTrue(logRecord.getAfterData().contains("new-value"));
    }

    @Test
    @DisplayName("方法抛异常 — status=1, stack_trace 有值, 异常继续抛出")
    void exceptionThrown_recordsErrorLog() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                "errorMethod", new Class[]{},
                new String[]{}, new Object[]{}, null
        );
        when(joinPoint.proceed()).thenThrow(new RuntimeException("测试异常"));

        OperationLog annotation = this.getClass().getMethod("errorMethod")
                .getAnnotation(OperationLog.class);

        when(logMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        // 异常应继续抛出
        assertThrows(RuntimeException.class, () -> aspect.around(joinPoint, annotation));

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(logMapper).insert(captor.capture());

        SysOperationLog logRecord = captor.getValue();
        assertEquals(1, logRecord.getStatus());
        assertEquals("测试异常", logRecord.getErrorMsg());
        assertNotNull(logRecord.getStackTrace());
        assertTrue(logRecord.getStackTrace().contains("RuntimeException"));
    }

    @Test
    @DisplayName("切面日志写入失败 — 不影响主流程")
    void logInsertFails_doesNotAffectMainFlow() throws Throwable {
        ProceedingJoinPoint joinPoint = mockJoinPoint(
                "sampleMethod", new Class[]{Long.class, String.class},
                new String[]{"id", "data"}, new Object[]{1L, "data"}, "ok"
        );
        OperationLog annotation = this.getClass().getMethod("sampleMethod", Long.class, String.class)
                .getAnnotation(OperationLog.class);

        // 模拟 insert 抛异常
        when(logMapper.insert(any(SysOperationLog.class))).thenThrow(new RuntimeException("DB 不可用"));

        // 主流程不受影响
        Object result = aspect.around(joinPoint, annotation);
        assertEquals("ok", result);
    }

    @Test
    @DisplayName("未登录用户 — operName 为空")
    void noAuthentication_operNameEmpty() throws Throwable {
        SecurityContextHolder.clearContext();

        ProceedingJoinPoint joinPoint = mockJoinPoint(
                "sampleMethod", new Class[]{Long.class, String.class},
                new String[]{"id", "data"}, new Object[]{1L, "data"}, "ok"
        );
        OperationLog annotation = this.getClass().getMethod("sampleMethod", Long.class, String.class)
                .getAnnotation(OperationLog.class);

        when(logMapper.insert(any(SysOperationLog.class))).thenReturn(1);

        aspect.around(joinPoint, annotation);

        ArgumentCaptor<SysOperationLog> captor = ArgumentCaptor.forClass(SysOperationLog.class);
        verify(logMapper).insert(captor.capture());

        assertEquals("", captor.getValue().getOperName());
        assertNull(captor.getValue().getBusinessUnitType());
    }
}
