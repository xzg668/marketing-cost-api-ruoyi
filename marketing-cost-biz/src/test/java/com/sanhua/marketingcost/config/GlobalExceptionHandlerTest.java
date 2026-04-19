package com.sanhua.marketingcost.config;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;

import static org.junit.jupiter.api.Assertions.*;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    @DisplayName("BadCredentialsException — 返回 '用户名或密码错误'")
    void handleBadCredentials_returnsCorrectMessage() {
        CommonResult<Void> response = handler.handleBadCredentials(
                new BadCredentialsException("Bad credentials"));

        assertFalse(response.isSuccess());
        assertEquals("用户名或密码错误", response.getMsg());
    }

    @Test
    @DisplayName("BadCredentialsException — 业务单元错误返回明确提示")
    void handleBadCredentials_businessUnitMessage_returnsOriginalMessage() {
        CommonResult<Void> response = handler.handleBadCredentials(
                new BadCredentialsException("当前账号不属于所选业务单元"));

        assertFalse(response.isSuccess());
        assertEquals("当前账号不属于所选业务单元", response.getMsg());
    }

    @Test
    @DisplayName("DisabledException — 返回 '账户已禁用'")
    void handleDisabled_returnsCorrectMessage() {
        CommonResult<Void> response = handler.handleDisabled(
                new DisabledException("User is disabled"));

        assertFalse(response.isSuccess());
        assertEquals("账户已禁用", response.getMsg());
    }

    @Test
    @DisplayName("AccessDeniedException — 返回 '权限不足'")
    void handleAccessDenied_returnsCorrectMessage() {
        CommonResult<Void> response = handler.handleAccessDenied(
                new AccessDeniedException("Access denied"));

        assertFalse(response.isSuccess());
        assertEquals("权限不足", response.getMsg());
    }

    @Test
    @DisplayName("通用异常 — 返回 '服务器内部错误'")
    void handleException_returnsInternalError() {
        CommonResult<Void> response = handler.handleException(
                new RuntimeException("something went wrong"));

        assertFalse(response.isSuccess());
        assertEquals("服务器内部错误", response.getMsg());
    }

    @Test
    @DisplayName("所有认证异常返回的 data 为 null")
    void authExceptions_dataIsNull() {
        assertNull(handler.handleBadCredentials(new BadCredentialsException("")).getData());
        assertNull(handler.handleDisabled(new DisabledException("")).getData());
        assertNull(handler.handleAccessDenied(new AccessDeniedException("")).getData());
    }
}
