-- =============================================================================
-- V175: 显示包装组件价格菜单
-- -----------------------------------------------------------------------------
-- 包装组件价格属于价格源管理。历史 deploy 菜单基线曾把该菜单 visible 写成 '1'，
-- 导致页面和接口存在但侧边栏不可见，本脚本幂等修复已有环境。
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
SELECT
  40449, '包装组件价格', price_root.menu_id, 90, '/price/package-component',
  'price/package-component/index', 1, '0', 'C', '0', '0', 'price:package-component:list',
  '#', 'admin', NOW(), 'V175', NOW(),
  '包装组件价格：价格源管理下的包装组件月度结构、价格和缺口清单', NULL
FROM (
  SELECT menu_id
  FROM sys_menu
  WHERE (path IN ('price', '/price') OR menu_name = '价格源管理')
    AND menu_type = 'M'
  ORDER BY CASE WHEN parent_id = 0 THEN 0 ELSE 1 END,
           CASE WHEN path IN ('price', '/price') THEN 0 ELSE 1 END,
           menu_id
  LIMIT 1
) price_root
WHERE NOT EXISTS (
  SELECT 1
  FROM sys_menu existing_menu
  WHERE existing_menu.menu_id = 40449
     OR existing_menu.path = '/price/package-component'
     OR existing_menu.component = 'price/package-component/index'
     OR existing_menu.perms = 'price:package-component:list'
);

UPDATE sys_menu package_menu
JOIN (
  SELECT menu_id
  FROM sys_menu
  WHERE (path IN ('price', '/price') OR menu_name = '价格源管理')
    AND menu_type = 'M'
  ORDER BY CASE WHEN parent_id = 0 THEN 0 ELSE 1 END,
           CASE WHEN path IN ('price', '/price') THEN 0 ELSE 1 END,
           menu_id
  LIMIT 1
) price_root
SET package_menu.parent_id = price_root.menu_id,
    package_menu.order_num = 90,
    package_menu.path = '/price/package-component',
    package_menu.component = 'price/package-component/index',
    package_menu.visible = '0',
    package_menu.status = '0',
    package_menu.perms = 'price:package-component:list',
    package_menu.icon = '#',
    package_menu.update_by = 'V175',
    package_menu.update_time = NOW(),
    package_menu.remark = '包装组件价格：价格源管理下的包装组件月度结构、价格和缺口清单'
WHERE package_menu.menu_type = 'C'
  AND (
    package_menu.menu_id = 40449
    OR package_menu.path = '/price/package-component'
    OR package_menu.component = 'price/package-component/index'
    OR package_menu.perms = 'price:package-component:list'
  );

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40450, '包装组件价格 查询', 40449, 1, '', NULL, 1, '0', 'F', '0', '0',
   'price:package-component:list', '#', 'admin', NOW(), 'V175', NOW(),
   '包装组件价格、结构快照列表查询权限', NULL),
  (40451, '包装组件价格 明细', 40449, 2, '', NULL, 1, '0', 'F', '0', '0',
   'price:package-component:detail', '#', 'admin', NOW(), 'V175', NOW(),
   '包装组件价格明细和结构快照明细查询权限', NULL),
  (40452, '包装组件价格 生成', 40449, 3, '', NULL, 1, '0', 'F', '0', '0',
   'price:package-component:generate', '#', 'admin', NOW(), 'V175', NOW(),
   '包装组件价格手动生成和重新取价权限', NULL),
  (40453, '包装组件缺口清单', 40449, 4, '', NULL, 1, '0', 'F', '0', '0',
   'price:package-component:gaps', '#', 'admin', NOW(), 'V175', NOW(),
   '包装组件缺结构/缺价清单查询权限', NULL)
ON DUPLICATE KEY UPDATE
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  icon = VALUES(icon),
  update_by = 'V175',
  update_time = NOW(),
  remark = VALUES(remark);

UPDATE sys_menu
SET parent_id = 40449,
    visible = '0',
    status = '0',
    icon = '#',
    update_by = 'V175',
    update_time = NOW()
WHERE menu_type = 'F'
  AND perms LIKE 'price:package-component:%';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40449), (1, 40450), (1, 40451), (1, 40452), (1, 40453),
  (10, 40449), (10, 40450), (10, 40451), (10, 40452), (10, 40453),
  (11, 40449), (11, 40450), (11, 40451), (11, 40452), (11, 40453);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 40449
FROM sys_role_menu rm
JOIN sys_menu price_root ON price_root.menu_id = rm.menu_id
WHERE (price_root.path IN ('price', '/price') OR price_root.menu_name = '价格源管理')
  AND price_root.menu_type = 'M';

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, child_menu.menu_id
FROM sys_role_menu rm
JOIN sys_menu child_menu ON child_menu.parent_id = 40449
WHERE rm.menu_id = 40449
  AND child_menu.menu_type = 'F';
