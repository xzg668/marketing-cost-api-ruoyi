-- =============================================================================
-- V101: 下线旧自制件管理菜单和权限
-- -----------------------------------------------------------------------------
-- MPPG-10 后，日常业务入口统一切到“价格源管理 / 制造件价格生成”。
-- 旧 lp_make_part_spec 只作为历史兼容数据保留，不再开放页面维护毛重、净重、
-- 原材料单价、废料代号和废料单价。
-- =============================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('/price/make', 'price/make', 'make')
     OR component IN ('price/make/index', 'views:price/make/index', 'pages:MakePartSpecPage')
     OR perms LIKE 'make:part:%'
);

DELETE FROM sys_menu
WHERE path IN ('/price/make', 'price/make', 'make')
   OR component IN ('price/make/index', 'views:price/make/index', 'pages:MakePartSpecPage')
   OR perms LIKE 'make:part:%';
