package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 字典类型请求 DTO
 */
@Data
public class SysDictTypeRequest {

    /** 字典名称 */
    @NotBlank(message = "字典名称不能为空")
    private String dictName;

    /** 字典类型编码（唯一） */
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    /** 状态（0正常 1停用） */
    private String status;

    /** 备注 */
    private String remark;
}
