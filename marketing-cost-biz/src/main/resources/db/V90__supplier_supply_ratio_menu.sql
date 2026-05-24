-- =============================================================================
-- V90: 供应商供货比例菜单与权限
-- -----------------------------------------------------------------------------
-- 菜单位置：基础数据 -> 供应关系 -> 供应商供货比例。
-- 说明：该页面维护供应关系主数据，供取价时选择供货比例最大的供应商。
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
   'V90：供应关系菜单所需基础数据根菜单', NULL)
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
  (40426, '供应关系', 40159, 15, '/base/supplier-relation', NULL, 1, '0', 'M',
   '0', '0', NULL, 'Connection', 'admin', NOW(), '', NOW(),
   '供应商关系类基础数据入口', NULL),
  (40427, '供应商供货比例', 40426, 1, '/base/supplier-relation/supply-ratio',
   'pages:SupplierSupplyRatioPage', 1, '0', 'C',
   '0', '0', 'base:supplier-supply-ratio:list', 'PieChart', 'admin', NOW(), '', NOW(),
   '维护供应商供货比例；导入时按物料代码、物料名称、供应商、型号去重', NULL),
  (40428, '供应商供货比例查看', 40427, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'base:supplier-supply-ratio:list', '#', 'admin', NOW(), '', NOW(),
   '供应商供货比例查询权限', NULL),
  (40429, '供应商供货比例导入', 40427, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'base:supplier-supply-ratio:import', '#', 'admin', NOW(), '', NOW(),
   '供货比例 Excel 导入权限', NULL),
  (40430, '供应商供货比例编辑', 40427, 3, '', NULL, 1, '0', 'F',
   '0', '0', 'base:supplier-supply-ratio:edit', '#', 'admin', NOW(), '', NOW(),
   '供应商供货比例编辑权限', NULL),
  (40431, '供应商供货比例删除', 40427, 4, '', NULL, 1, '0', 'F',
   '0', '0', 'base:supplier-supply-ratio:remove', '#', 'admin', NOW(), '', NOW(),
   '供应商供货比例逻辑删除权限', NULL)
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
  (1, 40159), (1, 40426), (1, 40427), (1, 40428), (1, 40429), (1, 40430), (1, 40431),
  (10, 40159), (10, 40426), (10, 40427), (10, 40428), (10, 40429), (10, 40430), (10, 40431),
  (11, 40159), (11, 40426), (11, 40427), (11, 40428), (11, 40429), (11, 40430), (11, 40431);

-- 兼容已有自定义角色：已能看到基础数据的角色，自动补齐供应关系菜单和页面权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40426
FROM sys_role_menu
WHERE menu_id IN (40159);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40427
FROM sys_role_menu
WHERE menu_id IN (40159, 40426);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40428
FROM sys_role_menu
WHERE menu_id IN (40427);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40429
FROM sys_role_menu
WHERE menu_id IN (40427);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40430
FROM sys_role_menu
WHERE menu_id IN (40427);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40431
FROM sys_role_menu
WHERE menu_id IN (40427);
