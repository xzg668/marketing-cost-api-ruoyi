-- 独立结算维护入口与权限，和 V332 结构分开。默认仅管理员授权，其他角色按职责配置。
START TRANSACTION;
INSERT INTO sys_menu (menu_name, parent_id, order_num, path, component, menu_type, visible, status,
  perms, icon, create_by, create_time, business_unit_type)
SELECT '结算价格', parent.menu_id, 46, '/price/settlement', 'price/settlement/index', 'C', '0', '0',
  'price:settlement:list', 'money', 'system', NOW(), NULL
FROM sys_menu parent WHERE parent.path = 'price' AND parent.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = 'price:settlement:list');
INSERT INTO sys_menu (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, business_unit_type)
SELECT action.label, entry.menu_id, action.sort, 'F', '0', '0', action.permission, 'system', NOW(), NULL
FROM (SELECT '结算原稿导入' label, 'price:settlement:import' permission, 'price:settlement:list' parent_permission, 1 sort) action
JOIN sys_menu entry ON entry.perms = action.parent_permission
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = action.permission);
INSERT INTO sys_role_menu(role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('price:settlement:list','price:settlement:import')
WHERE role.role_key = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id = role.role_id AND existing.menu_id = menu.menu_id);
COMMIT;
