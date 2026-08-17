-- QCBP-08：在现有报价单详情中发起协作的按钮权限；不创建新菜单或平行报价页面。
INSERT INTO sys_menu
  (menu_name, parent_id, order_num, path, component,
   is_frame, is_cache, menu_type, visible, status, perms, icon, create_by, create_time,
   update_by, update_time, remark, business_unit_type)
SELECT
  '报价协作任务发起', 206, 21, '#', NULL,
  1, 0, 'F', '1', '0', 'collaboration:task:create', '#', 'system', NOW(),
  'system', NOW(), '当前报价单产品行发起BOM、包装或价格协作', NULL
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms = 'collaboration:task:create');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT source_role.role_id, collaboration_menu.menu_id
FROM sys_role_menu source_role
JOIN sys_menu source_menu
  ON source_menu.menu_id = source_role.menu_id
 AND source_menu.perms = 'ingest:quote:bom-check'
JOIN sys_menu collaboration_menu
  ON collaboration_menu.perms = 'collaboration:task:create';
