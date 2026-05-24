-- =============================================================================
-- V99: 下线旧废料管理菜单和权限
-- -----------------------------------------------------------------------------
-- MPPG-08 后旧“价格源管理 -> 废料管理”页面与 API 已删除。
-- 废料价格只作为历史表/旧自制件管理遗留查询存在，不再开放页面维护入口。
-- =============================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('/price/scrap', 'price/scrap')
     OR component IN ('price/scrap/index', 'views:price/scrap/index', 'pages:PriceScrapPage')
     OR perms LIKE 'price:scrap:%'
);

DELETE FROM sys_menu
WHERE path IN ('/price/scrap', 'price/scrap')
   OR component IN ('price/scrap/index', 'views:price/scrap/index', 'pages:PriceScrapPage')
   OR perms LIKE 'price:scrap:%';
