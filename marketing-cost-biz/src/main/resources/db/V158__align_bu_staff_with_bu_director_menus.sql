-- =============================================================================
-- V158: BU_STAFF 操作权限对齐 BU_DIRECTOR，但不显示系统管理菜单
-- -----------------------------------------------------------------------------
-- 背景：
--   hfy 使用 role_id=11 / bu_staff。业务口径要求：
--     1. hfy 不显示“系统管理”菜单；
--     2. 除菜单展示差异外，其他接口/按钮操作权限与 panxh
--        (role_id=10 / bu_director) 一致。
--
-- 处理：
--   1. 复制 BU_DIRECTOR 当前所有非可见系统菜单绑定到 BU_STAFF。
--   2. 保持角色本身不变，不改用户角色归属。
--   3. 同步隐藏按钮权限（含 *:*:*），但排除系统管理目录和可见系统菜单。
-- =============================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40198, '业务操作全权限(通配)', 100, 999, '', '', 1, '0', 'F',
   '1', '0', '*:*:*', '#', 'admin', NOW(), '', NOW(),
   '隐藏按钮权限：业务角色接口操作通配，不作为可见菜单展示', NULL)
ON DUPLICATE KEY UPDATE
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (10, 40198),
  (11, 40198);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 11, rm.menu_id
FROM sys_role_menu rm
JOIN sys_menu m ON m.menu_id = rm.menu_id
WHERE rm.role_id = 10
  AND NOT (
    m.menu_type IN ('M', 'C')
    AND m.visible = '0'
    AND (
      m.menu_id = 100
      OR m.parent_id = 100
      OR m.path IN ('system', '/system')
    )
  );
