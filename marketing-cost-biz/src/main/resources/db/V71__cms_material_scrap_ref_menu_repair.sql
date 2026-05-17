-- =============================================================================
-- V71: repair CMS material-scrap mapping import menu
-- -----------------------------------------------------------------------------
-- Some running databases already had the CMS cost menu before the material-scrap
-- import page was added. Re-upsert the visible entry and grant it to every role
-- that can already access CMS cost data or CMS import pages.
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40420, 'CMS 回收废料映射', 40230, 4, '/base/cms-cost/material-scrap-refs',
   'pages:CmsMaterialScrapRefPage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Link', 'admin', NOW(), '', NOW(),
   'CMS原材料料号到回收废料料号的当前有效映射和Excel导入入口', NULL)
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
  (1, 40420),
  (10, 40420),
  (11, 40420);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40420
FROM sys_role_menu
WHERE menu_id IN (40230, 40231, 40237, 40239);
