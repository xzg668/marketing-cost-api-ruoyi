-- V128: 月度调价页面菜单和按钮权限
--
-- 页面挂到成本核算目录，前端组件复用 settlement/monthly-adjustment/index。
-- 旧结账目录下的 701 暂不删除，避免历史环境菜单引用断裂。

SET NAMES utf8mb4;

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
  (503, '月度调价', 500, 3, 'monthly-reprice', 'settlement/monthly-adjustment/index',
   'C', '0', '0', 'price:monthly-reprice:list', 'money', 'admin', NOW(), '', NOW(),
   '月度调价批次、任务、结果和审计查询', NULL),
  (5031, '月度调价查询', 503, 1, '', NULL, 'F', '0', '0',
   'price:monthly-reprice:list', '#', 'admin', NOW(), '', NOW(),
   '查看已确认月度调价批次和结果', NULL),
  (5032, '月度调价复核', 503, 2, '', NULL, 'F', '0', '0',
   'price:monthly-reprice:review', '#', 'admin', NOW(), '', NOW(),
   '查看待确认月度调价批次和结果', NULL),
  (5033, '月度调价操作', 503, 3, '', NULL, 'F', '0', '0',
   'price:monthly-reprice:operate', '#', 'admin', NOW(), '', NOW(),
   '发起、确认、取消、重试月度调价批次', NULL);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 503), (1, 5031), (1, 5032), (1, 5033),
  (10, 503), (10, 5031), (10, 5032), (10, 5033),
  (11, 503), (11, 5031);
