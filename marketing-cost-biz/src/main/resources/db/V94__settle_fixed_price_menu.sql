-- =============================================================================
-- V94  新增结算固定价菜单                                               2026-05-18
--
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   lp_price_fixed_item 通过 source_type 强隔离固定采购价与结算固定价。
--   FPT-05 新增“结算固定价”菜单，专门导入家用结算价9，并接收固定采购价5 的 U9 行。
-- =============================================================================

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  40432, '结算固定价', p.menu_id, 2, '/price/settle-fixed', 'price/settle-fixed/index',
  1, '0', 'C', '0', '0', 'price:fixed:list', 'money', 'admin', NOW(), '', NOW(),
  '结算固定价：导入家用结算价9，source_type=SETTLE_FIXED', NULL
FROM sys_menu p
WHERE p.menu_name = '价格源管理'
  AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 40432);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40433, '结算固定价 查询', 40432, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'price:fixed:list', '#', 'admin', NOW(), '', NOW(), '结算固定价查询权限', NULL),
  (40434, '结算固定价 导入', 40432, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'price:fixed:import', '#', 'admin', NOW(), '', NOW(), '结算固定价导入权限', NULL)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  perms = VALUES(perms),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40432), (1, 40433), (1, 40434),
  (10, 40432), (10, 40433), (10, 40434),
  (11, 40432), (11, 40433), (11, 40434);

-- 已能看到固定采购价的角色，自动补齐结算固定价菜单。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40432
FROM sys_role_menu
WHERE menu_id IN (SELECT menu_id FROM sys_menu WHERE component = 'price/fixed/index');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40433
FROM sys_role_menu
WHERE menu_id = 40432;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40434
FROM sys_role_menu
WHERE menu_id = 40432;

-- V94 结束
