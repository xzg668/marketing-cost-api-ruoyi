-- ============================================================
-- V7: 数据迁移脚本（T32）
-- 依赖: V3 / V4 / V5 已执行（用户表结构、权限表结构、角色/菜单种子）
-- 幂等: 全部使用 INSERT IGNORE + UPDATE WHERE 条件；重复执行无副作用
--
-- 目标：
--   1) 现有 admin 用户补齐若依新字段（nick_name / status / business_unit_type 等）
--   2) sys_user_role 适配到新角色体系（V5 已将 role_id=1 改为 admin 角色，此处校正）
--   3) 创建商用/家用业务单元测试账号（BU_STAFF），便于本地及联调验证数据隔离
--
-- 设计要点：
--   · admin.business_unit_type 置 NULL —— admin 可登任一业务单元（登录时前端下拉选择，写入 token）
--   · 测试账号 user_id 从 10 开始，避开 V3 种子用户 (user_id=1) 与未来管理员手工新增区间
--   · 密码统一使用 admin123 的 bcrypt（与 V3 admin 种子一致），方便本地开发验证
-- ============================================================

-- --------------------------------------------------------
-- 1. admin 用户字段补齐
--    V3 已插入 user_id=1，此处只做字段校正 —— 新增字段若为空则回填默认值
-- --------------------------------------------------------
UPDATE sys_user
SET nick_name          = IFNULL(NULLIF(nick_name, ''), '系统管理员'),
    status             = IFNULL(status, '0'),
    del_flag           = IFNULL(del_flag, '0'),
    business_unit_type = NULL,  -- admin 跨业务单元，登录时选择
    update_time        = NOW()
WHERE user_id = 1;

-- --------------------------------------------------------
-- 2. 用户-角色关联校正
--    V3 种子: (user_id=1, role_id=1)
--    V5 已将 role_id=1 更名为"系统管理员"(admin)，关联语义正确，此处兜底 INSERT IGNORE
-- --------------------------------------------------------
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 清理 admin 可能残留的旧已废弃角色关联（v1.1 的 role_id 2/3/4/5 已被 V5 软删除）
DELETE FROM sys_user_role
WHERE user_id = 1 AND role_id IN (2, 3, 4, 5);

-- --------------------------------------------------------
-- 3. 测试账号：商用 BU_STAFF / 家用 BU_STAFF
--    user_id: 10 / 11；角色 role_id=11 (bu_staff) — V5 已创建
--    密码: admin123（与 V3 admin 一致的 bcrypt）
-- --------------------------------------------------------
INSERT IGNORE INTO sys_user (
    user_id, user_name, password, nick_name,
    dept_id, business_unit_type, phone, sex, avatar,
    status, del_flag,
    create_by, create_time, update_by, update_time, remark
) VALUES
(10, 'commercial_staff',
 '$2a$10$GbHUEQG3Z.HxChIvMTwSq.c1et5tTdFDx4Q31wMh1ThNbXtdJMRme',
 '商用业务人员',
 NULL, 'COMMERCIAL', NULL, '0', '',
 '0', '0',
 'admin', NOW(), '', NOW(), '测试账号-商用事业部 BU_STAFF'),
(11, 'household_staff',
 '$2a$10$GbHUEQG3Z.HxChIvMTwSq.c1et5tTdFDx4Q31wMh1ThNbXtdJMRme',
 '家用业务人员',
 NULL, 'HOUSEHOLD', NULL, '0', '',
 '0', '0',
 'admin', NOW(), '', NOW(), '测试账号-家用事业部 BU_STAFF');

-- 绑定到 BU_STAFF 角色（role_id=11，V5 种子）
INSERT IGNORE INTO sys_user_role (user_id, role_id) VALUES
(10, 11),
(11, 11);

-- --------------------------------------------------------
-- 4. 数据校验（可选，用于部署后手动执行）
--   SELECT user_id, user_name, nick_name, business_unit_type, status
--     FROM sys_user WHERE del_flag = '0' ORDER BY user_id;
--   SELECT ur.user_id, u.user_name, ur.role_id, r.role_key
--     FROM sys_user_role ur
--     JOIN sys_user u ON u.user_id = ur.user_id
--     JOIN sys_role r ON r.role_id = ur.role_id
--    WHERE u.del_flag = '0' AND r.del_flag = '0';
-- --------------------------------------------------------
