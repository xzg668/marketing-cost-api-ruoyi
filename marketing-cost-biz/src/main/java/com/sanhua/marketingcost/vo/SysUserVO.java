package com.sanhua.marketingcost.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SysUserVO {
    private Long userId;
    private String userName;
    private String nickName;
    private String phone;
    private String sex;
    private Long deptId;
    private String deptName;
    /** 岗位ID列表，返回给前端做回显 */
    private List<Long> postIds;
    private String businessUnitType;
    private String status;
    private String remark;
    private LocalDateTime createTime;
    private List<RoleSimple> roles;

    @Data
    public static class RoleSimple {
        private Long roleId;
        private String roleName;
        private String roleKey;
    }
}
