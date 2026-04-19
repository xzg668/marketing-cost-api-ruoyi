package com.sanhua.marketingcost.dto.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class SysUserResetPasswordRequest {
    /** 新密码：仅接收反序列化，序列化时跳过，防止审计日志写入明文密码 */
    @NotBlank(message = "新密码不能为空")
    @Size(min = 6, max = 50, message = "密码长度 6~50")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String newPassword;
}
