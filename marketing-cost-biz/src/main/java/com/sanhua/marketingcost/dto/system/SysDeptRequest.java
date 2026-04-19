package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SysDeptRequest {

    @NotBlank(message = "部门名称不能为空")
    private String deptName;

    private Long parentId;

    private Integer orderNum;

    private String orgType;

    private String leader;

    private String phone;

    private String email;

    private String status;
}
