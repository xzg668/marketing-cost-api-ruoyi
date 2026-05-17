-- =====================================================================
-- V81: 月度调价和联动导入权限文案补齐
--
-- 目标：
--   1) 补齐 V5-09 使用的 price:factor-adjust:* 权限点。
--   2) 复用 V79 已有的 price:linked-item:import，并把菜单文案改成
--      “导入月度联动价与影响因素 Excel”，避免和“导入月度调价 Excel”混淆。
--   3) 管理员和现有业务角色默认具备按钮权限；其他角色仍按显式授权展示。
-- =====================================================================

SET NAMES utf8mb4;

UPDATE sys_menu
   SET menu_name = '导入月度联动价与影响因素 Excel',
       remark = '联动价格表主导入：导入月度联动价与影响因素 Excel，支持仅新增/覆盖生效',
       update_time = NOW()
 WHERE perms = 'price:linked-item:import';

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40280, '月度调价列表', 401, 80, '', NULL, 'F', '0', '0',
   'price:factor-adjust:list', '#', 'admin', NOW(), '', NOW(),
   '影响因素月度调价列表和影响因素月度价格查询'),
  (40281, '月度调价详情', 401, 81, '', NULL, 'F', '0', '0',
   'price:factor-adjust:detail', '#', 'admin', NOW(), '', NOW(),
   '影响因素月度调价批次详情和调价明细查询'),
  (40282, '导入月度调价 Excel', 401, 82, '', NULL, 'F', '0', '0',
   'price:factor-adjust:import', '#', 'admin', NOW(), '', NOW(),
   '影响因素表入口：导入月度调价 Excel，只处理影响因素价格'),
  (40283, '导出调价模板', 401, 83, '', NULL, 'F', '0', '0',
   'price:factor-adjust:export', '#', 'admin', NOW(), '', NOW(),
   '影响因素表入口：导出月度调价模板');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40280), (1, 40281), (1, 40282), (1, 40283),
  (10, 40280), (10, 40281), (10, 40282), (10, 40283),
  (11, 40280), (11, 40281), (11, 40282), (11, 40283);
