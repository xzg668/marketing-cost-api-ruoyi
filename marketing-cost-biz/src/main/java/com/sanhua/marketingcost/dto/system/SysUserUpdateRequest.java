package com.sanhua.marketingcost.dto.system;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class SysUserUpdateRequest {
    private String nickName;
    private String phone;
    private String sex;
    private Long deptId;
    /** 岗位ID列表，null 表示保持不变，空集合表示清空绑定 */
    private List<Long> postIds;
    private String status;
    private String remark;

    /** 密码：仅接收反序列化，序列化时跳过，防止审计日志写入明文密码 */
    @Size(min = 6, max = 50, message = "密码长度 6~50")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;
}
