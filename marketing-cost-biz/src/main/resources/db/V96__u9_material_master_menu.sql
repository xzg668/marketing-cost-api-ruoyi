-- =============================================================================
-- V96: U9 料品主档菜单与权限
-- -----------------------------------------------------------------------------
-- 这是 U9 数据统一目录，后续 U9 物料、BOM、组织、供应商等数据继续挂在
-- “基础数据 / U9基础数据” 下，避免 U9 来源数据分散在多个一级菜单里。
-- =============================================================================

SET NAMES utf8mb4;

-- 兼容历史库：当前动态菜单使用 40159 作为“基础数据”；旧初始化库可能只有 300。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40159, '基础数据', 0, 20, 'base', NULL, 1, '0', 'M',
   '0', '0', NULL, 'database', 'admin', NOW(), '', NOW(),
   'V96：U9 数据统一目录所需基础数据根菜单', NULL)
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

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40435, 'U9基础数据', 40159, 16, '/base/u9', NULL, 1, '0', 'M',
   '0', '0', NULL, 'Collection', 'admin', NOW(), '', NOW(),
   'U9 来源基础数据统一目录', NULL),
  (40436, '料品主档', 40435, 1, '/base/u9/material-master',
   'pages:U9MaterialMasterPage', 1, '0', 'C',
   '0', '0', 'base:u9-material:list', 'Tickets', 'admin', NOW(), '', NOW(),
   'U9 料品主档导入、查询和字段映射入口', NULL),
  (40437, '料品主档查询', 40436, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-material:list', '#', 'admin', NOW(), '', NOW(),
   'U9 料品主档列表和批次查询权限', NULL),
  (40438, '料品主档导入', 40436, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-material:import', '#', 'admin', NOW(), '', NOW(),
   'U9 料品主档 Excel 导入权限', NULL),
  (40441, '料品主档导出', 40436, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'base:u9-material:export', '#', 'admin', NOW(), '', NOW(),
   'U9 料品主档模板映射和导出权限', NULL)
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
  (1, 40159), (1, 40435), (1, 40436), (1, 40437), (1, 40438), (1, 40441),
  (10, 40159), (10, 40435), (10, 40436), (10, 40437), (10, 40438), (10, 40441),
  (11, 40159), (11, 40435), (11, 40436), (11, 40437), (11, 40438), (11, 40441);

-- 兼容已有自定义角色：已能看到基础数据的角色，自动补齐 U9 基础数据目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40435
FROM sys_role_menu
WHERE menu_id IN (40159);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40436
FROM sys_role_menu
WHERE menu_id IN (40159, 40435);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40437
FROM sys_role_menu
WHERE menu_id IN (40436);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40438
FROM sys_role_menu
WHERE menu_id IN (40436);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40441
FROM sys_role_menu
WHERE menu_id IN (40436);
