package com.sanhua.marketingcost.dto.system;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class SysMenuRequest {
    @NotBlank(message = "菜单名称不能为空")
    private String menuName;

    private Long parentId;

    private Integer orderNum;

    private String path;

    private String component;

    /** M=目录 C=菜单 F=按钮 */
    @NotBlank(message = "菜单类型不能为空")
    private String menuType;

    private String visible;

    private String status;

    private String perms;

    private String icon;

    private String businessUnitType;

    private String remark;
}
