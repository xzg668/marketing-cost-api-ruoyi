-- =============================================================================
-- V105: 新增包装组件价格菜单和权限
-- -----------------------------------------------------------------------------
-- 菜单位置：价格源管理 / 包装组件价格。
-- 说明：该页面查看包装组件月度结构快照、子件汇总价格和缺结构/缺价清单。
-- =============================================================================

SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path = '/price/package-component'
     OR component = 'price/package-component/index'
     OR perms LIKE 'price:package-component:%'
);

DELETE FROM sys_menu
WHERE path = '/price/package-component'
   OR component = 'price/package-component/index'
   OR perms LIKE 'price:package-component:%';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  40449, '包装组件价格', p.menu_id, 5, '/price/package-component', 'price/package-component/index',
  1, '0', 'C', '0', '0', 'price:package-component:list', '#', 'admin', NOW(), '', NOW(),
  '包装组件价格：查看月度结构快照、子件汇总价格和缺结构/缺价清单', NULL
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
  (40450, '包装组件价格 查询', 40449, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'price:package-component:list', '#', 'admin', NOW(), '', NOW(),
   '包装组件价格、结构快照列表查询权限', NULL),
  (40451, '包装组件价格 明细', 40449, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'price:package-component:detail', '#', 'admin', NOW(), '', NOW(),
   '包装组件价格明细和结构快照明细查询权限', NULL),
  (40452, '包装组件价格 生成', 40449, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'price:package-component:generate', '#', 'admin', NOW(), '', NOW(),
   '包装组件价格手动生成和重新取价权限', NULL),
  (40453, '包装组件缺口清单', 40449, 4, '', NULL, 1, '0', 'F',
   '0', '0', 'price:package-component:gaps', '#', 'admin', NOW(), '', NOW(),
   '包装组件缺结构/缺价清单查询权限', NULL)
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  is_frame = VALUES(is_frame),
  is_cache = VALUES(is_cache),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  icon = VALUES(icon),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40449), (1, 40450), (1, 40451), (1, 40452), (1, 40453),
  (10, 40449), (10, 40450), (10, 40451), (10, 40452), (10, 40453),
  (11, 40449), (11, 40450), (11, 40451), (11, 40452), (11, 40453);

-- 兼容已有自定义角色：已能看到价格源管理的角色，自动补齐包装组件价格菜单和按钮权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40449
FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id
  FROM sys_menu
  WHERE path IN ('price', '/price')
     OR menu_name = '价格源管理'
);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40450
FROM sys_role_menu
WHERE menu_id = 40449;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40451
FROM sys_role_menu
WHERE menu_id = 40449;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40452
FROM sys_role_menu
WHERE menu_id = 40449;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40453
FROM sys_role_menu
WHERE menu_id = 40449;
