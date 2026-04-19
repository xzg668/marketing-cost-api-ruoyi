package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 岗位管理请求 DTO
 */
@Data
public class SysPostRequest {

    /** 岗位编码（唯一） */
    @NotBlank(message = "岗位编码不能为空")
    private String postCode;

    /** 岗位名称 */
    @NotBlank(message = "岗位名称不能为空")
    private String postName;

    /** 显示顺序 */
    private Integer postSort;

    /** 状态（0正常 1停用） */
    private String status;

    /** 备注 */
    private String remark;
}
