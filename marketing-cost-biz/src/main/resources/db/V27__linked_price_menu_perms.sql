-- =====================================================================
-- V27: 联动价改造 —— 菜单按钮级权限 seed（T25）
--
-- 背景：
--   后端 controller 的三个关键端点已挂 @PreAuthorize：
--     POST /formula/preview          → price:linked-item:preview
--     GET  /variables                → price:variable:list
--     POST /finance-base/import      → price:finance-base:import
--   若 sys_menu 里没有对应按钮 F 行，admin 也拿不到这三条 perm，
--   @PreAuthorize 会 403，前端页面实际功能被卡。
--
-- 做法：
--   1) 向 sys_menu 追加 3 条 F 类按钮（parent_id = 401 联动价目录，方便后台管理）
--   2) 为 admin(1) / bu_director(10) / bu_staff(11) 三个角色批量关联这三条
--   3) INSERT IGNORE 幂等
-- =====================================================================

-- 1) 三条按钮级权限 ——
--    menu_id 分段：401 联动价目录，其下子菜单 4011-4014 已占，按钮起 40151 往后
--    注：business_unit_type 列由 V4 存储过程条件追加，测试容器里该过程被剥离，
--        INSERT 不显式列出该列以保持对两类环境都兼容（生产自动填 NULL）。
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40151, '公式预览', 401, 11, '', NULL, 'F', '0', '0',
   'price:linked-item:preview', '#', 'admin', NOW(), '', NOW(),
   'FormulaEditor 实时预览/后端 POST /formula/preview'),
  (40152, '变量列表查询', 401, 12, '', NULL, 'F', '0', '0',
   'price:variable:list', '#', 'admin', NOW(), '', NOW(),
   '变量管理查询/后端 GET /variables'),
  (40153, '影响因素表导入', 401, 13, '', NULL, 'F', '0', '0',
   'price:finance-base:import', '#', 'admin', NOW(), '', NOW(),
   '影响因素表 Excel 导入/后端 POST /finance-base/import');

-- 2) 角色-菜单关联
--    V5 的 admin 批量关联 `SELECT 1, menu_id FROM sys_menu` 在 V5 运行时执行过，
--    V27 新增的 3 条 menu 需要单独再关联一次，否则 admin 也拿不到。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40151), (1, 40152), (1, 40153),
  (10, 40151), (10, 40152), (10, 40153),
  (11, 40151), (11, 40152), (11, 40153);
