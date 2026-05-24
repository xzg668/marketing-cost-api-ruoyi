-- =============================================================================
-- V100: 新增制造件价格生成菜单和权限
-- -----------------------------------------------------------------------------
-- MPPG-09 新页面：价格源管理 / 制造件价格生成。
-- 旧自制件管理入口后续在 MPPG-10 单独删除或收口。
-- =============================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path = '/price/make-part-calc'
     OR component = 'price/make-calc/index'
     OR perms LIKE 'price:make-part-calc:%'
);

DELETE FROM sys_menu
WHERE path = '/price/make-part-calc'
   OR component = 'price/make-calc/index'
   OR perms LIKE 'price:make-part-calc:%';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  40445, '制造件价格生成', p.menu_id, 4, '/price/make-part-calc', 'price/make-calc/index',
  1, '0', 'C', '0', '0', 'price:make-part-calc:list', 'operation', 'admin', NOW(), '', NOW(),
  '制造件价格生成：读取 lp_make_part_price_calc_row，支持生成、查询、异常过滤和导出', NULL
FROM (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('price', '/price')
     OR menu_name = '价格源管理'
  ORDER BY CASE WHEN path IN ('price', '/price') THEN 0 ELSE 1 END, menu_id
  LIMIT 1
) p;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40446, '制造件价格生成 查询', 40445, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'price:make-part-calc:list', '#', 'admin', NOW(), '', NOW(), '制造件价格生成查询权限', NULL),
  (40447, '制造件价格生成 生成', 40445, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'price:make-part-calc:generate', '#', 'admin', NOW(), '', NOW(), '制造件价格生成权限', NULL),
  (40448, '制造件价格生成 导出', 40445, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'price:make-part-calc:export', '#', 'admin', NOW(), '', NOW(), '制造件价格生成导出权限', NULL)
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
  (1, 40445), (1, 40446), (1, 40447), (1, 40448),
  (10, 40445), (10, 40446), (10, 40447), (10, 40448),
  (11, 40445), (11, 40446), (11, 40447), (11, 40448);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40445
FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('price', '/price')
     OR menu_name = '价格源管理'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40446
FROM sys_role_menu
WHERE menu_id = 40445;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40447
FROM sys_role_menu
WHERE menu_id = 40445;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40448
FROM sys_role_menu
WHERE menu_id = 40445;
