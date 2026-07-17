-- =====================================================================
-- V189: 财务报价 Cu 基准管理页面菜单
--
-- 复用 sys_menu / sys_role_menu；不新增业务表。页面挂在现有“价格源管理”下，
-- V188 的查询/编辑按钮调整为该页面子权限。
-- =====================================================================

SET NAMES utf8mb4;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40477, '财务Cu报价基准', 400, 7, 'finance-cu-base', 'pages:FinanceCuBasePricePage',
   'C', '0', '0', 'cost:finance-cu-base:query', 'money', 'admin', NOW(), '', NOW(),
   '按当前业务单元维护财务报价Cu月度基准，页面口径为元/吨')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  perms = VALUES(perms),
  update_time = NOW(),
  remark = VALUES(remark);

UPDATE sys_menu
SET parent_id = 40477,
    update_time = NOW()
WHERE menu_id IN (40475, 40476);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES (1, 40477);

-- 若部署前已把查询按钮授予实际财务/报价角色，同步补齐页面菜单入口。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 40477
FROM sys_role_menu
WHERE menu_id = 40475;
