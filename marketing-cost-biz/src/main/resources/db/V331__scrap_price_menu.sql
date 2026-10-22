-- 废料价格独立入口。菜单配置与 V330 业务表结构分开执行。
-- 仅初始化管理员授权；其他角色通过现有角色权限页面配置。
START TRANSACTION;

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, business_unit_type)
SELECT '废料价格', parent.menu_id, 45, '/price/scrap', 'price/scrap/index', 'C', '0', '0',
       'price:scrap:list', 'table', 'system', NOW(), 'COMMERCIAL'
FROM sys_menu parent
WHERE parent.path = 'price' AND parent.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = 'price:scrap:list');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, business_unit_type)
SELECT '废料价格导入', entry.menu_id, 1, 'F', '0', '0', 'price:scrap:import', 'system', NOW(), 'COMMERCIAL'
FROM sys_menu entry
WHERE entry.component = 'price/scrap/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = 'price:scrap:import');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, business_unit_type)
SELECT '废料价格编辑', entry.menu_id, 2, 'F', '0', '0', 'price:scrap:edit', 'system', NOW(), 'COMMERCIAL'
FROM sys_menu entry
WHERE entry.component = 'price/scrap/index'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = 'price:scrap:edit');

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role JOIN sys_menu menu ON menu.perms IN ('price:scrap:list', 'price:scrap:import', 'price:scrap:edit')
WHERE role.role_key = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id = role.role_id AND existing.menu_id = menu.menu_id);

COMMIT;
