package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysDictData;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 字典数据 Service 单元测试
 */
class SysDictDataServiceImplTest {

    private SysDictDataMapper sysDictDataMapper;
    private SysDictDataServiceImpl service;

    @BeforeEach
    void setUp() {
        sysDictDataMapper = mock(SysDictDataMapper.class);
        service = new SysDictDataServiceImpl(sysDictDataMapper);
    }

    // ========== 分页查询 ==========

    @Test
    @DisplayName("分页查询 — 按字典类型精确匹配")
    void listPage_byDictType() {
        Page<SysDictData> page = new Page<>(1, 10);
        SysDictData data = new SysDictData();
        data.setDictCode(1L);
        data.setDictLabel("男");
        data.setDictValue("0");
        data.setDictType("sys_user_sex");
        page.setRecords(List.of(data));
        page.setTotal(1);
        when(sysDictDataMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysDictData> result = service.listPage(1, 10, "sys_user_sex", null, null);

        assertEquals(1, result.getTotal());
        assertEquals("男", result.getRecords().get(0).getDictLabel());
    }

    @Test
    @DisplayName("分页查询 — 带标签和状态过滤")
    void listPage_withLabelAndStatus() {
        Page<SysDictData> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(sysDictDataMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysDictData> result = service.listPage(1, 10, "sys_user_sex", "男", "0");

        assertEquals(0, result.getTotal());
    }

    // ========== 根据ID查询 ==========

    @Test
    @DisplayName("按ID查询 — 存在")
    void getById_exists() {
        SysDictData data = new SysDictData();
        data.setDictCode(1L);
        when(sysDictDataMapper.selectById(1L)).thenReturn(data);

        assertNotNull(service.getById(1L));
    }

    @Test
    @DisplayName("按ID查询 — 不存在")
    void getById_notExists() {
        when(sysDictDataMapper.selectById(999L)).thenReturn(null);

        assertNull(service.getById(999L));
    }

    // ========== 新增 ==========

    @Test
    @DisplayName("新增字典数据")
    void create_callsInsert() {
        SysDictData data = new SysDictData();
        data.setDictLabel("男");
        when(sysDictDataMapper.insert(data)).thenReturn(1);

        service.create(data);

        verify(sysDictDataMapper).insert(data);
    }

    // ========== 修改 ==========

    @Test
    @DisplayName("修改字典数据")
    void update_callsUpdate() {
        SysDictData data = new SysDictData();
        data.setDictCode(1L);
        when(sysDictDataMapper.updateById(data)).thenReturn(1);

        service.update(data);

        verify(sysDictDataMapper).updateById(data);
    }

    // ========== 删除 ==========

    @Test
    @DisplayName("删除字典数据")
    void delete_callsDelete() {
        when(sysDictDataMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);

        verify(sysDictDataMapper).deleteById(1L);
    }
}
