-- =====================================================================
-- V192: 修复财务 Cu 权限菜单与既有目录菜单 ID 冲突
--
-- 40475 原为“规则配置”，40476 原为“核算基础配置”。V188/V189 复用这两个
-- ID 写入财务 Cu 查询/维护权限，导致两个目录及其子菜单被错误挂到财务 Cu 页面下。
-- 本迁移使用新 ID 承接财务 Cu 按钮权限，并恢复原菜单目录和子菜单层级。
-- =====================================================================

SET NAMES utf8mb4;

START TRANSACTION;

-- 财务 Cu 页面下只挂按钮权限，避免再次占用目录菜单 ID。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40478, '财务Cu基准查询', 40477, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:finance-cu-base:query', '#', 'admin', NOW(), '', NOW(),
   '查询当前业务单元的财务报价Cu月度基准', NULL),
  (40479, '财务Cu基准维护', 40477, 2, '', NULL, 1, '0', 'F',
   '0', '0', 'cost:finance-cu-base:edit', '#', 'admin', NOW(), '', NOW(),
   '批量初始化或带原因调整财务报价Cu月度基准', NULL)
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
  remark = VALUES(remark),
  business_unit_type = VALUES(business_unit_type);

-- 页面可见角色继承查询权限；维护权限只承接同时拥有旧维护项和页面入口的角色，
-- 避免原“核算基础配置”目录权限误放大成财务 Cu 编辑权限。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 40478
FROM sys_role_menu
WHERE menu_id = 40477;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT page_role.role_id, 40479
FROM sys_role_menu page_role
JOIN sys_role_menu legacy_edit
  ON legacy_edit.role_id = page_role.role_id
 AND legacy_edit.menu_id = 40476
WHERE page_role.menu_id = 40477;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 40478), (1, 40479);

-- 恢复“规则配置”一级目录。
UPDATE sys_menu
SET menu_name = '规则配置',
    parent_id = 0,
    order_num = 50,
    path = 'rules',
    component = NULL,
    is_frame = 1,
    is_cache = '0',
    menu_type = 'M',
    visible = '0',
    status = '0',
    perms = NULL,
    icon = 'setting',
    update_by = 'V192',
    update_time = NOW(),
    remark = '菜单架构调整：规则类配置统一目录',
    business_unit_type = NULL
WHERE menu_id = 40475;

UPDATE sys_menu
SET parent_id = 40475,
    visible = '0',
    status = '0',
    update_by = 'V192',
    update_time = NOW()
WHERE menu_id IN (40171, 40239, 40187, 40421);

-- 恢复“核算基础配置”二级目录。
UPDATE sys_menu
SET menu_name = '核算基础配置',
    parent_id = 40159,
    order_num = 30,
    path = '/base/costing-config',
    component = NULL,
    is_frame = 1,
    is_cache = '0',
    menu_type = 'M',
    visible = '0',
    status = '0',
    perms = NULL,
    icon = 'setting',
    update_by = 'V192',
    update_time = NOW(),
    remark = '菜单架构调整：费率、属性、供应比例等核算基础配置统一目录',
    business_unit_type = NULL
WHERE menu_id = 40476;

UPDATE sys_menu
SET parent_id = 40476,
    visible = '0',
    status = '0',
    update_by = 'V192',
    update_time = NOW()
WHERE menu_id IN (40180, 40174, 40177, 40178, 40179, 40181, 40427);

-- 目录权限桥接：拥有任一子菜单的角色自动获得对应父目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40475
FROM sys_role_menu
WHERE menu_id IN (40171, 40239, 40187, 40421);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40476
FROM sys_role_menu
WHERE menu_id IN (40180, 40174, 40177, 40178, 40179, 40181, 40427);

COMMIT;
