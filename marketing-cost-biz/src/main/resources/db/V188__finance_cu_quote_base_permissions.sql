-- =====================================================================
-- V188: 财务报价 Cu 基准独立接口权限
--
-- 说明：
--   1. 复用 sys_menu / sys_role_menu，不新增权限业务表。
--   2. 权限按钮挂在现有“影响因素表”菜单下，但写接口与普通影响因素入口完全独立。
--   3. 默认只授予超级管理员；财务/报价岗位由系统管理员按实际角色分配。
-- =====================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40475, '财务Cu基准查询', 4014, 31, '', NULL, 'F', '0', '0',
   'cost:finance-cu-base:query', '#', 'admin', NOW(), '', NOW(),
   '查询当前业务单元的财务报价Cu月度基准'),
  (40476, '财务Cu基准维护', 4014, 32, '', NULL, 'F', '0', '0',
   'cost:finance-cu-base:edit', '#', 'admin', NOW(), '', NOW(),
   '批量初始化或带原因调整财务报价Cu月度基准')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  perms = VALUES(perms),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 40475), (1, 40476);
