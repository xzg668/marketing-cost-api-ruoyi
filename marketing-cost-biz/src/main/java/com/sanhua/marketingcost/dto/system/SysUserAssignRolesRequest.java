package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SysUserAssignRolesRequest {
    @NotNull(message = "角色ID列表不能为空")
    private List<Long> roleIds;
}
