package com.sanhua.marketingcost.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 sys_user
 */
@Data
@TableName("sys_user")
public class SysUser {
    @TableId(type = IdType.AUTO)
    private Long userId;
    private String userName;
    private String password;
    private String nickName;
    /** T04新增：部门ID */
    private Long deptId;
    /** T04新增：事业部类型（COMMERCIAL/HOUSEHOLD） */
    private String businessUnitType;
    /** T04新增：手机号码 */
    private String phone;
    /** T04新增：用户性别（0未知 1男 2女） */
    private String sex;
    /** T04新增：头像地址 */
    private String avatar;
    /** T04新增：岗位ID列表，逗号分隔 */
    private String postIds;
    private String status;
    private String delFlag;
    private String createBy;
    private LocalDateTime createTime;
    private String updateBy;
    private LocalDateTime updateTime;
    private String remark;
}
