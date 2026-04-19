package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysDictType;
import com.sanhua.marketingcost.mapper.SysDictDataMapper;
import com.sanhua.marketingcost.mapper.SysDictTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 字典类型 Service 单元测试
 */
class SysDictTypeServiceImplTest {

    private SysDictTypeMapper sysDictTypeMapper;
    private SysDictDataMapper sysDictDataMapper;
    private SysDictTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        sysDictTypeMapper = mock(SysDictTypeMapper.class);
        sysDictDataMapper = mock(SysDictDataMapper.class);
        service = new SysDictTypeServiceImpl(sysDictTypeMapper, sysDictDataMapper);
    }

    // ========== 分页查询 ==========

    @Test
    @DisplayName("分页查询 — 无过滤条件")
    void listPage_noFilter() {
        Page<SysDictType> page = new Page<>(1, 10);
        SysDictType type = new SysDictType();
        type.setDictId(1L);
        type.setDictName("用户性别");
        type.setDictType("sys_user_sex");
        page.setRecords(List.of(type));
        page.setTotal(1);
        when(sysDictTypeMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysDictType> result = service.listPage(1, 10, null, null, null);

        assertEquals(1, result.getTotal());
        assertEquals("sys_user_sex", result.getRecords().get(0).getDictType());
    }

    @Test
    @DisplayName("分页查询 — 带过滤条件")
    void listPage_withFilter() {
        Page<SysDictType> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(sysDictTypeMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysDictType> result = service.listPage(1, 10, "性别", "sex", "0");

        assertEquals(0, result.getTotal());
    }

    // ========== 查询所有 ==========

    @Test
    @DisplayName("查询所有正常状态字典类型")
    void listAll_returnsActive() {
        SysDictType type = new SysDictType();
        type.setDictId(1L);
        type.setStatus("0");
        when(sysDictTypeMapper.selectList(any())).thenReturn(List.of(type));

        List<SysDictType> result = service.listAll();

        assertEquals(1, result.size());
    }

    // ========== 根据ID查询 ==========

    @Test
    @DisplayName("按ID查询 — 存在")
    void getById_exists() {
        SysDictType type = new SysDictType();
        type.setDictId(1L);
        when(sysDictTypeMapper.selectById(1L)).thenReturn(type);

        assertNotNull(service.getById(1L));
    }

    @Test
    @DisplayName("按ID查询 — 不存在")
    void getById_notExists() {
        when(sysDictTypeMapper.selectById(999L)).thenReturn(null);

        assertNull(service.getById(999L));
    }

    // ========== 新增 ==========

    @Test
    @DisplayName("新增字典类型")
    void create_callsInsert() {
        SysDictType type = new SysDictType();
        type.setDictType("test_type");
        when(sysDictTypeMapper.insert(type)).thenReturn(1);

        service.create(type);

        verify(sysDictTypeMapper).insert(type);
    }

    // ========== 修改 ==========

    @Test
    @DisplayName("修改字典类型")
    void update_callsUpdate() {
        SysDictType type = new SysDictType();
        type.setDictId(1L);
        when(sysDictTypeMapper.updateById(type)).thenReturn(1);

        service.update(type);

        verify(sysDictTypeMapper).updateById(type);
    }

    // ========== 删除 ==========

    @Test
    @DisplayName("删除字典类型 — 同时删除该类型下字典数据")
    void delete_removesTypeAndData() {
        SysDictType type = new SysDictType();
        type.setDictId(1L);
        type.setDictType("sys_user_sex");
        when(sysDictTypeMapper.selectById(1L)).thenReturn(type);
        when(sysDictDataMapper.delete(any())).thenReturn(3);
        when(sysDictTypeMapper.deleteById(1L)).thenReturn(1);

        service.delete(1L);

        // 先删除字典数据
        verify(sysDictDataMapper).delete(any());
        // 再删除字典类型
        verify(sysDictTypeMapper).deleteById(1L);
    }

    @Test
    @DisplayName("删除字典类型 — 类型不存在时仍执行deleteById")
    void delete_typeNotFound_stillDeletesById() {
        when(sysDictTypeMapper.selectById(999L)).thenReturn(null);
        when(sysDictTypeMapper.deleteById(999L)).thenReturn(0);

        service.delete(999L);

        // 不查字典数据
        verify(sysDictDataMapper, never()).delete(any());
        verify(sysDictTypeMapper).deleteById(999L);
    }

    // ========== 编码唯一性校验 ==========

    @Test
    @DisplayName("字典类型编码唯一 — 不存在冲突")
    void isDictTypeUnique_noConflict() {
        when(sysDictTypeMapper.selectCount(any())).thenReturn(0L);

        assertTrue(service.isDictTypeUnique("new_type", null));
    }

    @Test
    @DisplayName("字典类型编码不唯一 — 存在冲突")
    void isDictTypeUnique_conflict() {
        when(sysDictTypeMapper.selectCount(any())).thenReturn(1L);

        assertFalse(service.isDictTypeUnique("sys_user_sex", null));
    }

    @Test
    @DisplayName("编辑时排除自身 — 编码唯一")
    void isDictTypeUnique_excludeSelf() {
        when(sysDictTypeMapper.selectCount(any())).thenReturn(0L);

        assertTrue(service.isDictTypeUnique("sys_user_sex", 1L));
    }
}
