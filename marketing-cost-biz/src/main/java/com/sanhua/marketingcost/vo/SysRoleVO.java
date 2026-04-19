package com.sanhua.marketingcost.vo;

import lombok.Data;

import java.util.Date;

@Data
public class SysRoleVO {
    private Long roleId;
    private String roleName;
    private String roleKey;
    private Integer roleSort;
    private String dataScope;
    private String status;
    private String remark;
    private Date createTime;
}
