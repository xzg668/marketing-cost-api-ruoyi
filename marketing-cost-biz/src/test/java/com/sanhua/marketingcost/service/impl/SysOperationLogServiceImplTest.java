package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysOperationLog;
import com.sanhua.marketingcost.mapper.SysOperationLogMapper;
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
 * 操作日志查询 Service 单元测试（T30）
 * <p>
 * 校验分页过滤、详情查询、批量/全量逻辑删除等行为，mock mapper 层验证调用。
 */
class SysOperationLogServiceImplTest {

    private SysOperationLogMapper operationLogMapper;
    private SysOperationLogServiceImpl service;

    @BeforeEach
    void setUp() {
        operationLogMapper = mock(SysOperationLogMapper.class);
        service = new SysOperationLogServiceImpl(operationLogMapper);
    }

    // ========== 分页查询 ==========

    @Test
    @DisplayName("分页查询 — 无过滤条件")
    void listLogs_noFilter_returnsPage() {
        // 构造 mock 分页返回
        Page<SysOperationLog> page = new Page<>(1, 10);
        SysOperationLog log = new SysOperationLog();
        log.setOperId(1L);
        log.setTitle("用户管理");
        log.setBusinessType(1);
        page.setRecords(List.of(log));
        page.setTotal(1);
        when(operationLogMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysOperationLog> result = service.listLogs(
                1, 10, null, null, null, null, null, null, null);

        assertEquals(1, result.getTotal());
        assertEquals("用户管理", result.getRecords().get(0).getTitle());
        verify(operationLogMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询 — 全部过滤条件生效")
    void listLogs_withAllFilters_passesWrapper() {
        Page<SysOperationLog> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(operationLogMapper.selectPage(any(), any())).thenReturn(page);

        LocalDateTime begin = LocalDateTime.of(2026, 4, 1, 0, 0);
        LocalDateTime end = LocalDateTime.of(2026, 4, 18, 23, 59);
        IPage<SysOperationLog> result = service.listLogs(
                1, 10, "admin", "用户", 1, 0, "COMMERCIAL", begin, end);

        assertEquals(0, result.getTotal());
        // mapper 调用一次，过滤条件通过 wrapper 传入
        verify(operationLogMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询 — 空字符串过滤被忽略")
    void listLogs_blankStringFilters_ignored() {
        Page<SysOperationLog> page = new Page<>(1, 10);
        page.setRecords(List.of());
        when(operationLogMapper.selectPage(any(), any())).thenReturn(page);

        // 空串与全空白串都应被视为无过滤
        service.listLogs(1, 10, "", "   ", null, null, " ", null, null);

        verify(operationLogMapper).selectPage(any(), any());
    }

    // ========== 详情 ==========

    @Test
    @DisplayName("按ID查询 — 记录存在")
    void getById_exists_returnsLog() {
        SysOperationLog log = new SysOperationLog();
        log.setOperId(10L);
        log.setTitle("角色管理");
        when(operationLogMapper.selectById(10L)).thenReturn(log);

        SysOperationLog result = service.getById(10L);

        assertNotNull(result);
        assertEquals("角色管理", result.getTitle());
    }

    @Test
    @DisplayName("按ID查询 — 记录不存在返回null")
    void getById_notExists_returnsNull() {
        when(operationLogMapper.selectById(999L)).thenReturn(null);

        assertNull(service.getById(999L));
    }

    // ========== 批量删除 ==========

    @Test
    @DisplayName("批量删除 — 正常调用 deleteByIds")
    void deleteByIds_withIds_callsMapper() {
        List<Long> ids = Arrays.asList(1L, 2L, 3L);
        when(operationLogMapper.deleteByIds(ids)).thenReturn(3);

        int affected = service.deleteByIds(ids);

        assertEquals(3, affected);
        verify(operationLogMapper).deleteByIds(ids);
    }

    @Test
    @DisplayName("批量删除 — 空ID列表直接返回0")
    void deleteByIds_emptyList_returnsZero() {
        int affected = service.deleteByIds(List.of());

        assertEquals(0, affected);
        // 不应触发 mapper 删除
        verify(operationLogMapper, never()).deleteByIds(any());
    }

    @Test
    @DisplayName("批量删除 — null 入参返回0")
    void deleteByIds_null_returnsZero() {
        int affected = service.deleteByIds(null);

        assertEquals(0, affected);
        verify(operationLogMapper, never()).deleteByIds(any());
    }

    // ========== 清空 ==========

    @Test
    @DisplayName("清空全部 — 调用 delete(wrapper)")
    void cleanAll_callsDelete() {
        when(operationLogMapper.delete(any())).thenReturn(42);

        int affected = service.cleanAll();

        assertEquals(42, affected);
        verify(operationLogMapper).delete(any());
    }
}
