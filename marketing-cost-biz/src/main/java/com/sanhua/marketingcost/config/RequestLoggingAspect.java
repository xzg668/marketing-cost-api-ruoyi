package com.sanhua.marketingcost.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.stream.Collectors;

@Aspect
@Component
public class RequestLoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(RequestLoggingAspect.class);
    private static final int PARAM_MAX_LENGTH = 512;
    private static final int RESPONSE_MAX_LENGTH = 1024;
    private static final long SLOW_THRESHOLD_MS = 300000;

    private final ObjectMapper objectMapper;

    public RequestLoggingAspect(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
    @Around("within(com.sanhua.marketingcost.controller..*)")
    public Object logRequest(ProceedingJoinPoint joinPoint) throws Throwable {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String method = "UNKNOWN";
        String uri = "UNKNOWN";
        if (attrs != null) {
            HttpServletRequest request = attrs.getRequest();
            method = request.getMethod();
            uri = request.getRequestURI();
        }

        String params = serializeArgs(joinPoint.getArgs(), PARAM_MAX_LENGTH);
        log.info(">>> {} {} | params={}", method, uri, params);

        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long duration = System.currentTimeMillis() - start;

        String responseStr = truncate(toJson(result), RESPONSE_MAX_LENGTH);
        if (duration >= SLOW_THRESHOLD_MS) {
            log.warn("<<< {} {} | {}ms (SLOW) | response={}", method, uri, duration, responseStr);
        } else {
            log.info("<<< {} {} | {}ms | response={}", method, uri, duration, responseStr);
        }
        return result;
    }

    private String serializeArgs(Object[] args, int maxLength) {
        if (args == null || args.length == 0) {
            return "[]";
        }
        String result = Arrays.stream(args)
                .map(this::argToString)
                .collect(Collectors.joining(", ", "[", "]"));
        return truncate(result, maxLength);
    }

    private String argToString(Object arg) {
        if (arg == null) return "null";
        if (arg instanceof HttpServletRequest) return "[HttpServletRequest]";
        if (arg instanceof HttpServletResponse) return "[HttpServletResponse]";
        if (arg instanceof MultipartFile f) return "[MultipartFile:" + f.getOriginalFilename() + "]";
        return toJson(arg);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "null";
        return str.length() > maxLength ? str.substring(0, maxLength) + "...(truncated)" : str;
    }
}
