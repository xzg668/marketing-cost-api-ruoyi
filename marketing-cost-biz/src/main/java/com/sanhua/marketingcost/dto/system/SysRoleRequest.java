package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SysRoleRequest {
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    @NotBlank(message = "角色编码不能为空")
    private String roleKey;

    private Integer roleSort;
    private String dataScope;
    private String status;
    private String remark;
}
