package com.sanhua.marketingcost.entity.system;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 用户与岗位关联表 sys_user_post
 * T04新建表
 */
@Data
@TableName("sys_user_post")
public class SysUserPost {

    /** 用户ID */
    private Long userId;

    /** 岗位ID */
    private Long postId;
}
