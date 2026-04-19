package com.sanhua.marketingcost.mapper;

import com.sanhua.marketingcost.entity.system.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * T06 验收测试：验证新建 Entity 字段映射和 Mapper 接口可正常实例化
 * 使用 mock 方式，不依赖数据库
 */
class T06SystemMapperTest {

    @Test
    @DisplayName("SysDept Entity 字段赋值与读取")
    void sysDept_fieldsAccessible() {
        SysDept dept = new SysDept();
        dept.setDeptId(1L);
        dept.setParentId(0L);
        dept.setDeptName("测试部门");
        dept.setOrgType("BRANCH");
        dept.setStatus("0");
        dept.setDeleted(0);

        assertEquals(1L, dept.getDeptId());
        assertEquals("测试部门", dept.getDeptName());
        assertEquals("BRANCH", dept.getOrgType());
        assertEquals(0, dept.getDeleted());
    }

    @Test
    @DisplayName("SysPost Entity 字段赋值与读取")
    void sysPost_fieldsAccessible() {
        SysPost post = new SysPost();
        post.setPostId(1L);
        post.setPostCode("CEO");
        post.setPostName("董事长");
        post.setPostSort(1);
        post.setStatus("0");

        assertEquals("CEO", post.getPostCode());
        assertEquals("董事长", post.getPostName());
    }

    @Test
    @DisplayName("SysUserPost Entity 字段赋值与读取")
    void sysUserPost_fieldsAccessible() {
        SysUserPost up = new SysUserPost();
        up.setUserId(1L);
        up.setPostId(1L);

        assertEquals(1L, up.getUserId());
        assertEquals(1L, up.getPostId());
    }

    @Test
    @DisplayName("SysDictType Entity 字段赋值与读取")
    void sysDictType_fieldsAccessible() {
        SysDictType dt = new SysDictType();
        dt.setDictId(1L);
        dt.setDictName("用户性别");
        dt.setDictType("sys_user_sex");
        dt.setStatus("0");

        assertEquals("用户性别", dt.getDictName());
        assertEquals("sys_user_sex", dt.getDictType());
    }

    @Test
    @DisplayName("SysDictData Entity 字段赋值与读取")
    void sysDictData_fieldsAccessible() {
        SysDictData dd = new SysDictData();
        dd.setDictCode(1L);
        dd.setDictLabel("男");
        dd.setDictValue("1");
        dd.setDictType("sys_user_sex");
        dd.setIsDefault("Y");

        assertEquals("男", dd.getDictLabel());
        assertEquals("Y", dd.getIsDefault());
    }

    @Test
    @DisplayName("SysOperationLog Entity 字段赋值与读取")
    void sysOperationLog_fieldsAccessible() {
        SysOperationLog log = new SysOperationLog();
        log.setOperId(1L);
        log.setTitle("用户管理");
        log.setBusinessType(1);
        log.setMethod("com.sanhua.UserController.add()");
        log.setRequestMethod("POST");
        log.setStatus(0);
        log.setCostTime(120L);

        assertEquals("用户管理", log.getTitle());
        assertEquals(120L, log.getCostTime());
    }

    @Test
    @DisplayName("SysLoginLog Entity 字段赋值与读取")
    void sysLoginLog_fieldsAccessible() {
        SysLoginLog log = new SysLoginLog();
        log.setInfoId(1L);
        log.setUserName("admin");
        log.setIpaddr("127.0.0.1");
        log.setStatus("0");
        log.setMsg("登录成功");

        assertEquals("admin", log.getUserName());
        assertEquals("登录成功", log.getMsg());
    }

    @Test
    @DisplayName("LpCollaborationToken Entity 字段赋值与读取")
    void lpCollaborationToken_fieldsAccessible() {
        LpCollaborationToken token = new LpCollaborationToken();
        token.setTokenId(1L);
        token.setToken("abc-123");
        token.setUserId(1L);
        token.setTokenType("COLLABORATION");
        token.setStatus("0");

        assertEquals("abc-123", token.getToken());
        assertEquals("COLLABORATION", token.getTokenType());
    }

    @Test
    @DisplayName("所有 Mapper 接口可被 mock 实例化（验证接口定义正确）")
    void allMappers_canBeMocked() {
        // 验证 Mapper 接口定义正确，能被 Mockito 实例化
        SysDeptMapper deptMapper = mock(SysDeptMapper.class);
        SysPostMapper postMapper = mock(SysPostMapper.class);
        SysUserPostMapper userPostMapper = mock(SysUserPostMapper.class);
        SysDictTypeMapper dictTypeMapper = mock(SysDictTypeMapper.class);
        SysDictDataMapper dictDataMapper = mock(SysDictDataMapper.class);
        SysOperationLogMapper operLogMapper = mock(SysOperationLogMapper.class);
        SysLoginLogMapper loginLogMapper = mock(SysLoginLogMapper.class);
        LpCollaborationTokenMapper tokenMapper = mock(LpCollaborationTokenMapper.class);

        // 验证 selectCount 方法存在（继承自 BaseMapper）
        when(deptMapper.selectCount(null)).thenReturn(0L);
        when(postMapper.selectCount(null)).thenReturn(0L);
        when(userPostMapper.selectCount(null)).thenReturn(0L);
        when(dictTypeMapper.selectCount(null)).thenReturn(0L);
        when(dictDataMapper.selectCount(null)).thenReturn(0L);
        when(operLogMapper.selectCount(null)).thenReturn(0L);
        when(loginLogMapper.selectCount(null)).thenReturn(0L);
        when(tokenMapper.selectCount(null)).thenReturn(0L);

        assertEquals(0L, deptMapper.selectCount(null));
        assertEquals(0L, tokenMapper.selectCount(null));
    }
}
