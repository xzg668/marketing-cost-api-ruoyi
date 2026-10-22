-- 型号—板片材质对照表：菜单初始化独立于结构与业务导入。
-- 使用数据库生成 ID，避免与历史目录冲突；仅管理员默认取得维护权限。
START TRANSACTION;

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, business_unit_type)
SELECT '型号—板片材质对照表', parent.menu_id, 80, '/base/plateMaterialMapping',
       'base/plateMaterialMapping/index', 'C', '0', '0', 'base:plate-material:list',
       'table', 'system', NOW(), NULL
FROM sys_menu parent
JOIN sys_menu root ON root.menu_id = parent.parent_id AND root.path = 'base' AND root.menu_type = 'M'
WHERE parent.path = '/base/costing-config' AND parent.menu_type = 'M'
  AND NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = 'base:plate-material:list');

INSERT INTO sys_menu
  (menu_name, parent_id, order_num, menu_type, visible, status, perms, create_by, create_time, business_unit_type)
SELECT action.label, entry.menu_id, action.sort, 'F', '0', '0', action.permission, 'system', NOW(), NULL
FROM (SELECT '型号材质新增' label, 'base:plate-material:add' permission, 1 sort
  UNION ALL SELECT '型号材质编辑', 'base:plate-material:edit', 2
  UNION ALL SELECT '型号材质导入', 'base:plate-material:import', 3) action
JOIN sys_menu entry ON entry.perms = 'base:plate-material:list'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu existing WHERE existing.perms = action.permission);

INSERT INTO sys_role_menu(role_id, menu_id)
SELECT role.role_id, menu.menu_id FROM sys_role role JOIN sys_menu menu
  ON menu.perms IN ('base:plate-material:list','base:plate-material:add','base:plate-material:edit','base:plate-material:import')
WHERE role.role_key = 'admin'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu existing WHERE existing.role_id = role.role_id AND existing.menu_id = menu.menu_id);
COMMIT;
