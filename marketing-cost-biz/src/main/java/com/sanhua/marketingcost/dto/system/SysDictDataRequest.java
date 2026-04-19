package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 字典数据请求 DTO
 */
@Data
public class SysDictDataRequest {

    /** 字典类型 */
    @NotBlank(message = "字典类型不能为空")
    private String dictType;

    /** 字典标签 */
    @NotBlank(message = "字典标签不能为空")
    private String dictLabel;

    /** 字典键值 */
    @NotBlank(message = "字典键值不能为空")
    private String dictValue;

    /** 字典排序 */
    private Integer dictSort;

    /** 样式属性 */
    private String cssClass;

    /** 表格回显样式 */
    private String listClass;

    /** 是否默认（Y/N） */
    private String isDefault;

    /** 状态（0正常 1停用） */
    private String status;

    /** 备注 */
    private String remark;
}
