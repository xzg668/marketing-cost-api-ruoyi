package com.sanhua.marketingcost.dto.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SysUserCreateRequest {
    @NotBlank(message = "用户名不能为空")
    @Size(min = 2, max = 30, message = "用户名长度 2~30")
    private String userName;

    /** 密码：仅接收反序列化，序列化时跳过，防止审计日志写入明文密码 */
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 50, message = "密码长度 6~50")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    private String nickName;
    private String phone;
    private String sex;
    private Long deptId;
    /** 岗位ID列表，前端多选传入，后端会拼接为逗号分隔字符串存入 sys_user.post_ids */
    private List<Long> postIds;
    private String status;
    private String remark;
}
