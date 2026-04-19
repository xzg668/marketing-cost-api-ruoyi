-- ============================================================
-- V8: sys_menu 补齐 is_cache 列
-- 背景：
--   · 若依标准 sys_menu 本就包含 is_cache 字段（V3 已声明），但线上数据库
--     因历史手工迁移/导入遗漏，导致 MyBatis Plus selectList 时报
--     "Unknown column 'is_cache'"，/auth/routers 返回空菜单。
--   · 幂等：add_column_if_not_exists 存在时跳过。
-- ============================================================

CALL add_column_if_not_exists('sys_menu', 'is_cache',
    "char(1) DEFAULT '0' COMMENT '是否缓存（0缓存 1不缓存）' AFTER `is_frame`");

-- 已有菜单统一置为"缓存"（0）；若依默认行为
UPDATE sys_menu SET is_cache = '0' WHERE is_cache IS NULL;
