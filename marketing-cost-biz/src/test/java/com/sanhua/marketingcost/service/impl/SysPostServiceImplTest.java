package com.sanhua.marketingcost.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sanhua.marketingcost.entity.system.SysPost;
import com.sanhua.marketingcost.mapper.SysPostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 岗位管理 Service 单元测试
 */
class SysPostServiceImplTest {

    private SysPostMapper sysPostMapper;
    private SysPostServiceImpl sysPostService;

    @BeforeEach
    void setUp() {
        sysPostMapper = mock(SysPostMapper.class);
        sysPostService = new SysPostServiceImpl(sysPostMapper);
    }

    // ========== 分页查询 ==========

    @Test
    @DisplayName("分页查询 — 无搜索条件")
    void listPosts_noFilter_returnsPage() {
        Page<SysPost> page = new Page<>(1, 10);
        SysPost post = new SysPost();
        post.setPostId(1L);
        post.setPostCode("CEO");
        post.setPostName("董事长");
        page.setRecords(List.of(post));
        page.setTotal(1);
        when(sysPostMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysPost> result = sysPostService.listPosts(1, 10, null, null, null);

        assertEquals(1, result.getTotal());
        assertEquals("CEO", result.getRecords().get(0).getPostCode());
        verify(sysPostMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("分页查询 — 带编码和名称过滤")
    void listPosts_withFilter_returnsFiltered() {
        Page<SysPost> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(sysPostMapper.selectPage(any(), any())).thenReturn(page);

        IPage<SysPost> result = sysPostService.listPosts(1, 10, "HR", "人事", "0");

        assertEquals(0, result.getTotal());
        verify(sysPostMapper).selectPage(any(), any());
    }

    // ========== 查询所有 ==========

    @Test
    @DisplayName("查询所有正常状态岗位")
    void listAll_returnsActivePosts() {
        SysPost post = new SysPost();
        post.setPostId(1L);
        post.setPostCode("CEO");
        post.setStatus("0");
        when(sysPostMapper.selectList(any())).thenReturn(List.of(post));

        List<SysPost> result = sysPostService.listAll();

        assertEquals(1, result.size());
        verify(sysPostMapper).selectList(any());
    }

    // ========== 根据ID查询 ==========

    @Test
    @DisplayName("按ID查询 — 岗位存在")
    void getById_exists_returnsPost() {
        SysPost post = new SysPost();
        post.setPostId(1L);
        post.setPostCode("CEO");
        when(sysPostMapper.selectById(1L)).thenReturn(post);

        SysPost result = sysPostService.getById(1L);

        assertNotNull(result);
        assertEquals("CEO", result.getPostCode());
    }

    @Test
    @DisplayName("按ID查询 — 岗位不存在返回null")
    void getById_notExists_returnsNull() {
        when(sysPostMapper.selectById(999L)).thenReturn(null);

        SysPost result = sysPostService.getById(999L);

        assertNull(result);
    }

    // ========== 新增 ==========

    @Test
    @DisplayName("新增岗位 — 调用insert")
    void createPost_callsInsert() {
        SysPost post = new SysPost();
        post.setPostCode("HR");
        post.setPostName("人事");
        when(sysPostMapper.insert(post)).thenReturn(1);

        sysPostService.createPost(post);

        verify(sysPostMapper).insert(post);
    }

    // ========== 修改 ==========

    @Test
    @DisplayName("修改岗位 — 调用updateById")
    void updatePost_callsUpdate() {
        SysPost post = new SysPost();
        post.setPostId(1L);
        post.setPostCode("CEO");
        when(sysPostMapper.updateById(post)).thenReturn(1);

        sysPostService.updatePost(post);

        verify(sysPostMapper).updateById(post);
    }

    // ========== 删除 ==========

    @Test
    @DisplayName("删除岗位 — 调用deleteById")
    void deletePost_callsDelete() {
        when(sysPostMapper.deleteById(1L)).thenReturn(1);

        sysPostService.deletePost(1L);

        verify(sysPostMapper).deleteById(1L);
    }

    // ========== 编码唯一性校验 ==========

    @Test
    @DisplayName("编码唯一 — 不存在相同编码")
    void isPostCodeUnique_noConflict_returnsTrue() {
        when(sysPostMapper.selectCount(any())).thenReturn(0L);

        boolean result = sysPostService.isPostCodeUnique("NEW_CODE", null);

        assertTrue(result);
    }

    @Test
    @DisplayName("编码不唯一 — 存在相同编码")
    void isPostCodeUnique_conflict_returnsFalse() {
        when(sysPostMapper.selectCount(any())).thenReturn(1L);

        boolean result = sysPostService.isPostCodeUnique("CEO", null);

        assertFalse(result);
    }

    @Test
    @DisplayName("编辑时排除自身 — 编码唯一")
    void isPostCodeUnique_excludeSelf_returnsTrue() {
        when(sysPostMapper.selectCount(any())).thenReturn(0L);

        boolean result = sysPostService.isPostCodeUnique("CEO", 1L);

        assertTrue(result);
    }

    @Test
    @DisplayName("编辑时排除自身 — 与其他岗位编码冲突")
    void isPostCodeUnique_excludeSelf_conflictWithOther_returnsFalse() {
        when(sysPostMapper.selectCount(any())).thenReturn(1L);

        boolean result = sysPostService.isPostCodeUnique("CEO", 2L);

        assertFalse(result);
    }
}
