package com.sanhua.marketingcost.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.toolkit.Constants;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.sanhua.marketingcost.annotation.DataScope;
import com.sanhua.marketingcost.entity.SysUser;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("""
            SELECT *
            FROM sys_user
            WHERE user_name = #{username}
              AND del_flag = '0'
            LIMIT 1
            """)
    SysUser selectByUsername(@Param("username") String username);

    @DataScope
    @Select("""
            <script>
            SELECT *
            FROM sys_user
            ${ew.customSqlSegment}
            </script>
            """)
    <P extends IPage<SysUser>> P selectUserPage(P page, @Param(Constants.WRAPPER) Wrapper<SysUser> queryWrapper);

    @DataScope
    @Override
    List<SysUser> selectList(@Param("ew") Wrapper<SysUser> queryWrapper);
}
