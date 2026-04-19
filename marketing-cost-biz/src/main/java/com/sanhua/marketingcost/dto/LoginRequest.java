package com.sanhua.marketingcost.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录请求体。
 * <p>
 * v1.3 方案 C：businessUnitType 必填 —— 所有角色（含 admin）登录时必须选择业务单元。
 */
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /** 业务单元类型：COMMERCIAL（商用）/ HOUSEHOLD（家用），所有角色必选 */
    @NotBlank(message = "请选择业务单元")
    @Pattern(regexp = "^(COMMERCIAL|HOUSEHOLD)$", message = "业务单元取值必须为 COMMERCIAL 或 HOUSEHOLD")
    private String businessUnitType;
}
