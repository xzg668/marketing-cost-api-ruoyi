package com.sanhua.marketingcost.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.annotation.OperationLog;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
import com.sanhua.marketingcost.security.BusinessUnitContext;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDateTime;

/**
 * 操作日志 AOP 切面
 * <p>
 * 拦截标注了 @OperationLog 的 Controller 方法，自动记录操作日志到 sys_operation_log 表。
 * <ul>
 *   <li>基础字段：操作人、模块、操作类型、URL、方法、参数、结果、耗时、IP</li>
 *   <li>增强字段：business_unit_type、target_id、before_data、after_data、stack_trace</li>
 *   <li>日志写入异步执行，不影响主流程性能</li>
 *   <li>切面自身异常不影响主流程返回</li>
 * </ul>
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);

    private final SysOperationLogMapper operationLogMapper;
    private final ObjectMapper objectMapper;

    public OperationLogAspect(SysOperationLogMapper operationLogMapper, ObjectMapper objectMapper) {
        this.operationLogMapper = operationLogMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 环绕通知：拦截标注了 @OperationLog 的方法
     */
    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint joinPoint, OperationLog operationLog) throws Throwable {
        // 在主线程中提取上下文信息（HttpServletRequest、SecurityContext 等在异步线程中不可用）
        String username = getCurrentUsername();
        String businessUnitType = BusinessUnitContext.getCurrentBusinessUnitType();
        String operUrl = "";
        String operIp = "";
        String requestMethodStr = "";
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            operUrl = request.getRequestURI();
            operIp = getClientIp(request);
            requestMethodStr = request.getMethod();
        }

        // 提取目标ID
        String targetId = extractTargetId(joinPoint, operationLog.targetIdParam());

        // 提取请求参数
        String operParam = truncate(toJson(joinPoint.getArgs()), 2000);

        // 方法签名
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        String methodName = signature.getDeclaringTypeName() + "." + signature.getName() + "()";

        long startTime = System.currentTimeMillis();
        Object result = null;
        Throwable error = null;

        try {
            result = joinPoint.proceed();
            return result;
        } catch (Throwable ex) {
            error = ex;
            throw ex;
        } finally {
            long costTime = System.currentTimeMillis() - startTime;
            try {
                // 构建日志记录并异步保存
                SysOperationLog logRecord = buildLog(
                        operationLog, username, businessUnitType,
                        operUrl, operIp, requestMethodStr, methodName,
                        operParam, targetId, result, error, costTime
                );
                saveLogAsync(logRecord);
            } catch (Exception ex) {
                // 切面异常不影响主流程
                log.warn("操作日志记录失败", ex);
            }
        }
    }

    /**
     * 构建操作日志记录
     */
    private SysOperationLog buildLog(OperationLog annotation, String username,
                                     String businessUnitType, String operUrl,
                                     String operIp, String requestMethod,
                                     String methodName, String operParam,
                                     String targetId, Object result,
                                     Throwable error, long costTime) {
        SysOperationLog logRecord = new SysOperationLog();
        logRecord.setTitle(annotation.module());
        logRecord.setBusinessType(annotation.operationType().getCode());
        logRecord.setMethod(methodName);
        logRecord.setRequestMethod(requestMethod);
        logRecord.setOperatorType(1); // 后台用户
        logRecord.setOperName(username);
        logRecord.setOperUrl(truncate(operUrl, 500));
        logRecord.setOperIp(truncate(operIp, 128));
        logRecord.setOperParam(operParam);
        logRecord.setOperTime(LocalDateTime.now());
        logRecord.setCostTime(costTime);
        logRecord.setBusinessUnitType(businessUnitType);
        logRecord.setTargetId(truncate(targetId, 100));

        if (error != null) {
            // 异常情况
            logRecord.setStatus(1);
            logRecord.setErrorMsg(truncate(error.getMessage(), 2000));
            logRecord.setStackTrace(truncate(getStackTrace(error), 4000));
        } else {
            logRecord.setStatus(0);
            logRecord.setJsonResult(truncate(toJson(result), 2000));
        }

        // recordDiff 时，after_data 记录请求体
        if (annotation.recordDiff()) {
            logRecord.setAfterData(operParam);
        }

        return logRecord;
    }

    /**
     * 异步保存日志到数据库
     */
    @Async("costRunExecutor")
    public void saveLogAsync(SysOperationLog logRecord) {
        try {
            operationLogMapper.insert(logRecord);
        } catch (Exception ex) {
            log.warn("操作日志异步写入失败", ex);
        }
    }

    /**
     * 获取当前登录用户名
     */
    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return "";
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return principal.toString();
    }

    /**
     * 从方法参数中提取目标ID
     *
     * @param joinPoint     切入点
     * @param targetIdParam 注解指定的参数名
     * @return 目标ID字符串
     */
    private String extractTargetId(JoinPoint joinPoint, String targetIdParam) {
        if (!StringUtils.hasText(targetIdParam)) {
            return null;
        }
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] args = joinPoint.getArgs();
        for (int i = 0; i < parameters.length; i++) {
            if (targetIdParam.equals(parameters[i].getName())) {
                return args[i] == null ? null : args[i].toString();
            }
        }
        // 参数名可能被编译器擦除，尝试按方法签名的参数名
        String[] paramNames = signature.getParameterNames();
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length; i++) {
                if (targetIdParam.equals(paramNames[i])) {
                    return args[i] == null ? null : args[i].toString();
                }
            }
        }
        return null;
    }

    /**
     * 获取客户端 IP 地址
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (!StringUtils.hasText(ip) || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // X-Forwarded-For 可能包含多个 IP，取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 对象序列化为 JSON 字符串
     */
    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }

    /**
     * 获取异常堆栈字符串
     */
    private String getStackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    /**
     * 截断字符串到指定长度
     */
    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) {
            return str;
        }
        return str.substring(0, maxLength);
    }
}
