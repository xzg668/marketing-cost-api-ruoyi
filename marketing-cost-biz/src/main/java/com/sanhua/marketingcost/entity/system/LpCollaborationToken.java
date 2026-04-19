package com.sanhua.marketingcost.entity.system;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协作令牌表 lp_collaboration_token
 * T04新建表
 */
@Data
@TableName("lp_collaboration_token")
public class LpCollaborationToken {

    @TableId(type = IdType.AUTO)
    private Long tokenId;

    /** 令牌值 */
    private String token;

    /** 关联用户ID */
    private Long userId;

    /** 令牌类型 */
    private String tokenType;

    /** 过期时间 */
    private LocalDateTime expireTime;

    /** 状态（0有效 1已使用 2已过期） */
    private String status;

    /** 备注 */
    private String remark;

    /** 删除标志（0存在 1删除） */
    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
