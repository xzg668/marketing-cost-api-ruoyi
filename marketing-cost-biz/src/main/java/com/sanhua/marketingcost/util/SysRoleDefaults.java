package com.sanhua.marketingcost.util;

import com.sanhua.marketingcost.entity.SysRole;

/**
 * 内置角色规范化。
 */
public final class SysRoleDefaults {

    private static final String ADMIN_ROLE_KEY = "admin";

    private SysRoleDefaults() {
    }

    public static SysRole normalize(SysRole role) {
        if (role == null) {
            return null;
        }
        if (ADMIN_ROLE_KEY.equalsIgnoreCase(role.getRoleKey())) {
            role.setRoleName("系统管理员");
            role.setDataScope("2");
            if (role.getRemark() == null || role.getRemark().isBlank()
                    || role.getRemark().contains("超级管理员")) {
                role.setRemark("管理本单元用户 + 全局系统配置（菜单/字典/部门/岗位）；可分配任意角色（含 admin）");
            }
        }
        return role;
    }
}
