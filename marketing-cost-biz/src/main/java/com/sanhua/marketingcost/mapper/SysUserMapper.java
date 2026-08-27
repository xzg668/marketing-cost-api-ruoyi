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

    /**
     * 报价协作按OA产品行上的技术负责人名称做严格匹配。
     *
     * <p>这里故意不做模糊查询，也不跨事业部兜底，避免把任务误派给同名或其他事业部人员。
     */
    @Select("""
            SELECT DISTINCT u.*
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.user_id
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE u.del_flag = '0'
              AND u.status = '0'
              AND u.business_unit_type = #{businessUnitType}
              AND (u.user_name = #{identity} OR u.nick_name = #{identity})
              AND LOWER(r.role_key) = 'technical_collaborator'
              AND r.status = '0' AND r.del_flag = '0'
            ORDER BY u.user_id
            """)
    List<SysUser> selectActiveByIdentityAndBusinessUnit(
            @Param("identity") String identity,
            @Param("businessUnitType") String businessUnitType);

    @Select("""
            SELECT DISTINCT u.*
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.user_id
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE u.user_id = #{userId}
              AND u.del_flag = '0'
              AND u.status = '0'
              AND u.business_unit_type = #{businessUnitType}
              AND LOWER(r.role_key) = 'technical_collaborator'
              AND r.status = '0' AND r.del_flag = '0'
            LIMIT 1
            """)
    SysUser selectActiveByIdAndBusinessUnit(
            @Param("userId") Long userId,
            @Param("businessUnitType") String businessUnitType);

    /** 当前报价产品可手工选择的技术协作账号。 */
    @Select("""
            SELECT DISTINCT u.*
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.user_id
            JOIN sys_role r ON r.role_id = ur.role_id
            WHERE u.del_flag = '0'
              AND u.status = '0'
              AND u.business_unit_type = #{businessUnitType}
              AND LOWER(r.role_key) = 'technical_collaborator'
              AND r.status = '0' AND r.del_flag = '0'
            ORDER BY COALESCE(NULLIF(u.nick_name, ''), u.user_name), u.user_id
            """)
    List<SysUser> selectActiveCollaboratorsByBusinessUnit(
            @Param("businessUnitType") String businessUnitType);

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
