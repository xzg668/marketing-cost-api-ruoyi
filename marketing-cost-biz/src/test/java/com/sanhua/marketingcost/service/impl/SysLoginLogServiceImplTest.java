package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysLoginLog;
import com.sanhua.marketingcost.mapper.SysLoginLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 登录日志查询 Service 单元测试（T31）
 */
class SysLoginLogServiceImplTest {

    private SysLoginLogMapper loginLogMapper;
    private SysLoginLogServiceImpl service;

    @BeforeEach
    void setUp() {
        loginLogMapper = mock(SysLoginLogMapper.class);
        service = new SysLoginLogServiceImpl(loginLogMapper);
    }

    // ========== 分页查询 ==========

    @Test
    @DisplayName("分页查询 — 无过滤条件")
    void listLogs_noFilter_returnsPage() {
        Page<SysLoginLog> page = new Page<>(1, 10);
        SysLoginLog log = new SysLoginLog();
        log.setInfoId(1L);
        log.setUserName("admin");
        log.setStatus("0");
        page.setRecords(List.of(log));
        page.setTotal(1);
        when(loginLogMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysLoginLog> result = service.listLogs(1, 10, null, null, null, null, null);

        assertEquals(1, result.getTotal());
        assertEquals("admin", result.getRecords().get(0).getUserName());
        verify(loginLogMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询 — 带用户名/IP/状态/时间过滤")
    void listLogs_withFilters_callsMapper() {
        Page<SysLoginLog> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(loginLogMapper.selectPage(any(), any())).thenReturn(page);

        LocalDateTime begin = LocalDateTime.of(2026, 4, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 18, 23, 59);
        service.listLogs(1, 10, "admin", "127.0.0.1", "0", begin, end);

        verify(loginLogMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询 — 空白过滤条件被忽略")
    void listLogs_blankFilters_ignored() {
        Page<SysLoginLog> page = new Page<>(1, 10);
        when(loginLogMapper.selectPage(any(), any())).thenReturn(page);

        service.listLogs(1, 10, "", "  ", "", null, null);

        verify(loginLogMapper).selectPage(any(), any());
    }

    // ========== 详情 ==========

    @Test
    @DisplayName("按ID查询 — 记录存在")
    void getById_exists_returnsLog() {
        SysLoginLog log = new SysLoginLog();
        log.setInfoId(99L);
        log.setUserName("alice");
        when(loginLogMapper.selectById(99L)).thenReturn(log);

        SysLoginLog result = service.getById(99L);

        assertNotNull(result);
        assertEquals("alice", result.getUserName());
    }

    @Test
    @DisplayName("按ID查询 — 记录不存在返回null")
    void getById_notExists_returnsNull() {
        when(loginLogMapper.selectById(999L)).thenReturn(null);

        assertNull(service.getById(999L));
    }

    // ========== 批量删除 ==========

    @Test
    @DisplayName("批量删除 — 正常调用 deleteByIds")
    void deleteByIds_withIds_callsMapper() {
        List<Long> ids = Arrays.asList(10L, 11L);
        when(loginLogMapper.deleteByIds(ids)).thenReturn(2);

        assertEquals(2, service.deleteByIds(ids));
        verify(loginLogMapper).deleteByIds(ids);
    }

    @Test
    @DisplayName("批量删除 — 空列表返回0")
    void deleteByIds_empty_returnsZero() {
        assertEquals(0, service.deleteByIds(List.of()));
        verify(loginLogMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("批量删除 — null 入参返回0")
    void deleteByIds_null_returnsZero() {
        assertEquals(0, service.deleteByIds(null));
        verify(loginLogMapper, never()).deleteByIds(any());
    }

    // ========== 清空 ==========

    @Test
    @DisplayName("清空全部 — 调用 delete(wrapper)")
    void cleanAll_callsDelete() {
        when(loginLogMapper.delete(any())).thenReturn(7);

        assertEquals(7, service.cleanAll());
        verify(loginLogMapper).delete(any());
    }
}
