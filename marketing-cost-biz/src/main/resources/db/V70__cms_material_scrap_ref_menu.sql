-- =============================================================================
-- V70: CMS 原材料对应回收废料菜单
-- -----------------------------------------------------------------------------
-- 页面挂在“基础数据 -> CMS 成本数据”下，负责维护 CMS 原材料料号到回收废料料号
-- 的当前有效映射；废料回收价仍由“价格源管理 -> 废料管理”维护。
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

-- 兼容已存在的自定义角色：已经能看到 CMS 成本数据的角色，自动补齐本页面权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40420
FROM sys_role_menu
WHERE menu_id IN (40230);
