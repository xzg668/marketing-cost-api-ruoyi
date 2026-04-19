package com.sanhua.marketingcost.vo;

import lombok.Data;

import java.util.List;

/**
 * 当前登录用户信息 VO（/api/v1/auth/me 响应体）。
 * <p>
 * v1.3 新增 permissions 和 businessUnitType 字段。
 */
@Data
public class UserInfoVO {
    /** 用户ID */
    private Long userId;
    /** 用户名 */
    private String username;
    /** 昵称（显示名） */
    private String nickName;
    /** 角色编码列表（不含 ROLE_ 前缀，如 ADMIN / BU_STAFF） */
    private List<String> roles;
    /** 权限标识列表（如 system:user:list；admin 返回 ["*:*:*"]） */
    private List<String> permissions;
    /** 当前会话业务单元：COMMERCIAL / HOUSEHOLD */
    private String businessUnitType;
}
