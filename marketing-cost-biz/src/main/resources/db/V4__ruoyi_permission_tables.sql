-- ============================================================
-- V4: 若依权限体系 DDL 迁移
-- 依赖: V3__auth_tables.sql 已执行
-- 幂等: 所有 ALTER 通过存储过程检查列是否存在；所有 CREATE 使用 IF NOT EXISTS
-- ============================================================

-- --------------------------------------------------------
-- 辅助存储过程：幂等添加列
-- --------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_not_exists;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def    VARCHAR(500)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- --------------------------------------------------------
-- 1. sys_user 扩展字段
-- V3 已有: user_id, user_name, password, nick_name, status, del_flag,
--          create_by, create_time, update_by, update_time, remark
-- --------------------------------------------------------
CALL add_column_if_not_exists('sys_user', 'dept_id',
    "bigint DEFAULT NULL COMMENT '部门ID' AFTER `nick_name`");
CALL add_column_if_not_exists('sys_user', 'business_unit_type',
    "varchar(20) DEFAULT NULL COMMENT '事业部类型（COMMERCIAL/HOUSEHOLD）' AFTER `dept_id`");
CALL add_column_if_not_exists('sys_user', 'phone',
    "varchar(20) DEFAULT NULL COMMENT '手机号码' AFTER `business_unit_type`");
CALL add_column_if_not_exists('sys_user', 'sex',
    "char(1) DEFAULT '0' COMMENT '用户性别（0未知 1男 2女）' AFTER `phone`");
CALL add_column_if_not_exists('sys_user', 'avatar',
    "varchar(500) DEFAULT '' COMMENT '头像地址' AFTER `sex`");
CALL add_column_if_not_exists('sys_user', 'post_ids',
    "varchar(255) DEFAULT NULL COMMENT '岗位ID列表，逗号分隔' AFTER `avatar`");

-- --------------------------------------------------------
-- 2. sys_role 扩展字段
-- V3 已有: role_id, role_name, role_key, role_sort, status, del_flag,
--          create_by, create_time, update_by, update_time, remark
-- --------------------------------------------------------
CALL add_column_if_not_exists('sys_role', 'data_scope',
    "char(1) DEFAULT '1' COMMENT '数据范围（1全部 2自定义 3本部门 4本部门及以下 5仅本人）' AFTER `role_sort`");

-- --------------------------------------------------------
-- 3. sys_menu 扩展字段
-- V3 已有: menu_id, menu_name, parent_id, order_num, path, component,
--          is_frame, is_cache, menu_type, visible, status, perms, icon,
--          create_by, create_time, update_by, update_time, remark
-- --------------------------------------------------------
CALL add_column_if_not_exists('sys_menu', 'business_unit_type',
    "varchar(20) DEFAULT NULL COMMENT '事业部类型（COMMERCIAL/HOUSEHOLD），NULL表示通用菜单' AFTER `remark`");

-- --------------------------------------------------------
-- 4. sys_dept 部门表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dept` (
    `dept_id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '部门ID',
    `parent_id`          bigint       DEFAULT 0               COMMENT '父部门ID',
    `ancestors`          varchar(500) DEFAULT ''               COMMENT '祖级列表',
    `dept_name`          varchar(100) NOT NULL                 COMMENT '部门名称',
    `org_type`           varchar(20)  DEFAULT NULL             COMMENT '组织类型',
    `order_num`          int          DEFAULT 0                COMMENT '显示顺序',
    `leader`             varchar(64)  DEFAULT NULL             COMMENT '负责人',
    `phone`              varchar(20)  DEFAULT NULL             COMMENT '联系电话',
    `email`              varchar(100) DEFAULT NULL             COMMENT '邮箱',
    `status`             char(1)      NOT NULL DEFAULT '0'     COMMENT '部门状态（0正常 1停用）',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- --------------------------------------------------------
-- 5. sys_post 岗位表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_post` (
    `post_id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '岗位ID',
    `post_code`          varchar(64)  NOT NULL                 COMMENT '岗位编码',
    `post_name`          varchar(100) NOT NULL                 COMMENT '岗位名称',
    `post_sort`          int          NOT NULL DEFAULT 0       COMMENT '显示顺序',
    `status`             char(1)      NOT NULL DEFAULT '0'     COMMENT '岗位状态（0正常 1停用）',
    `remark`             varchar(500) DEFAULT NULL             COMMENT '备注',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`post_id`),
    UNIQUE KEY `uk_post_code` (`post_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位信息表';

-- --------------------------------------------------------
-- 6. sys_user_post 用户岗位关联表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_user_post` (
    `user_id`            bigint NOT NULL COMMENT '用户ID',
    `post_id`            bigint NOT NULL COMMENT '岗位ID',
    PRIMARY KEY (`user_id`, `post_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户与岗位关联表';

-- --------------------------------------------------------
-- 7. sys_dict_type 字典类型表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_type` (
    `dict_id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '字典主键',
    `dict_name`          varchar(100) NOT NULL                 COMMENT '字典名称',
    `dict_type`          varchar(100) NOT NULL                 COMMENT '字典类型',
    `status`             char(1)      NOT NULL DEFAULT '0'     COMMENT '状态（0正常 1停用）',
    `remark`             varchar(500) DEFAULT NULL             COMMENT '备注',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`dict_id`),
    UNIQUE KEY `uk_dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典类型表';

-- --------------------------------------------------------
-- 8. sys_dict_data 字典数据表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_dict_data` (
    `dict_code`          bigint       NOT NULL AUTO_INCREMENT COMMENT '字典编码',
    `dict_sort`          int          DEFAULT 0                COMMENT '字典排序',
    `dict_label`         varchar(100) NOT NULL                 COMMENT '字典标签',
    `dict_value`         varchar(100) NOT NULL                 COMMENT '字典键值',
    `dict_type`          varchar(100) NOT NULL                 COMMENT '字典类型',
    `css_class`          varchar(100) DEFAULT NULL             COMMENT '样式属性',
    `list_class`         varchar(100) DEFAULT NULL             COMMENT '表格回显样式',
    `is_default`         char(1)      DEFAULT 'N'             COMMENT '是否默认（Y是 N否）',
    `status`             char(1)      NOT NULL DEFAULT '0'     COMMENT '状态（0正常 1停用）',
    `remark`             varchar(500) DEFAULT NULL             COMMENT '备注',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`dict_code`),
    KEY `idx_dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='字典数据表';

-- --------------------------------------------------------
-- 9. sys_operation_log 操作日志表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_operation_log` (
    `oper_id`            bigint        NOT NULL AUTO_INCREMENT COMMENT '日志主键',
    `title`              varchar(50)   DEFAULT ''               COMMENT '模块标题',
    `business_type`      int           DEFAULT 0                COMMENT '业务类型（0其它 1新增 2修改 3删除）',
    `method`             varchar(200)  DEFAULT ''               COMMENT '方法名称',
    `request_method`     varchar(10)   DEFAULT ''               COMMENT '请求方式',
    `operator_type`      int           DEFAULT 0                COMMENT '操作类别（0其它 1后台用户 2手机端用户）',
    `oper_name`          varchar(64)   DEFAULT ''               COMMENT '操作人员',
    `dept_name`          varchar(64)   DEFAULT ''               COMMENT '部门名称',
    `oper_url`           varchar(500)  DEFAULT ''               COMMENT '请求URL',
    `oper_ip`            varchar(128)  DEFAULT ''               COMMENT '主机地址',
    `oper_location`      varchar(255)  DEFAULT ''               COMMENT '操作地点',
    `oper_param`         varchar(2000) DEFAULT ''               COMMENT '请求参数',
    `json_result`        varchar(2000) DEFAULT ''               COMMENT '返回参数',
    `status`             int           DEFAULT 0                COMMENT '操作状态（0正常 1异常）',
    `error_msg`          varchar(2000) DEFAULT ''               COMMENT '错误消息',
    `oper_time`          datetime      DEFAULT NULL             COMMENT '操作时间',
    `cost_time`          bigint        DEFAULT 0                COMMENT '消耗时间（毫秒）',
    `deleted`            tinyint       NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`oper_id`),
    KEY `idx_oper_time` (`oper_time`),
    KEY `idx_business_type` (`business_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志记录';

-- --------------------------------------------------------
-- 10. sys_login_log 登录日志表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_login_log` (
    `info_id`            bigint       NOT NULL AUTO_INCREMENT COMMENT '访问ID',
    `user_name`          varchar(64)  DEFAULT ''               COMMENT '用户账号',
    `ipaddr`             varchar(128) DEFAULT ''               COMMENT '登录IP地址',
    `login_location`     varchar(255) DEFAULT ''               COMMENT '登录地点',
    `browser`            varchar(50)  DEFAULT ''               COMMENT '浏览器类型',
    `os`                 varchar(50)  DEFAULT ''               COMMENT '操作系统',
    `status`             char(1)      DEFAULT '0'              COMMENT '登录状态（0成功 1失败）',
    `msg`                varchar(255) DEFAULT ''               COMMENT '提示消息',
    `login_time`         datetime     DEFAULT NULL             COMMENT '访问时间',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`info_id`),
    KEY `idx_login_time` (`login_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='系统访问记录';

-- --------------------------------------------------------
-- 11. lp_collaboration_token 协作令牌表
-- --------------------------------------------------------
CREATE TABLE IF NOT EXISTS `lp_collaboration_token` (
    `token_id`           bigint       NOT NULL AUTO_INCREMENT COMMENT '令牌ID',
    `token`              varchar(255) NOT NULL                 COMMENT '令牌值',
    `user_id`            bigint       NOT NULL                 COMMENT '关联用户ID',
    `token_type`         varchar(50)  DEFAULT 'COLLABORATION'  COMMENT '令牌类型',
    `expire_time`        datetime     NOT NULL                 COMMENT '过期时间',
    `status`             char(1)      NOT NULL DEFAULT '0'     COMMENT '状态（0有效 1已使用 2已过期）',
    `remark`             varchar(500) DEFAULT NULL             COMMENT '备注',
    `deleted`            tinyint      NOT NULL DEFAULT 0       COMMENT '删除标志（0存在 1删除）',
    `created_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`         datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`token_id`),
    UNIQUE KEY `uk_token` (`token`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_expire_time` (`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='协作令牌表';

-- --------------------------------------------------------
-- 清理辅助存储过程
-- --------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_not_exists;
