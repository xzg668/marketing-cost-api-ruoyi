package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-菜单关联表 sys_role_menu
 */
@Data
@TableName("sys_role_menu")
public class SysRoleMenu {
    /** 角色ID */
    private Long roleId;
    /** 菜单ID */
    private Long menuId;
}
