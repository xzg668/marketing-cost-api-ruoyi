-- =====================================================================
-- V33: Plan B T9b —— 价格变量运维 CRUD 按钮权限 seed
--
-- 背景：
--   Plan B T9a 新增了 4 个受保权端点，挂在 @PreAuthorize 上：
--     POST   /api/v1/price-linked/variables           → price:variable:admin
--     PUT    /api/v1/price-linked/variables/{id}      → price:variable:admin
--     DELETE /api/v1/price-linked/variables/{id}      → price:variable:admin
--     GET    /api/v1/price-linked/variables/catalog   → price:variable:catalog
--
--   其中 catalog 端点（T15 遗留）此前未 seed，admin 也拿不到；
--   新增的 admin CRUD 同样需要 seed，否则前端"价格变量配置"页 @PreAuthorize 403。
--
-- 做法：
--   1) 向 sys_menu 追加 2 条 F 类按钮（parent_id=401 联动价目录，紧跟 40151~40153）
--   2) 给 admin(1) / bu_director(10) / bu_staff(11) 三角色批量关联
--   3) INSERT IGNORE 幂等，重跑安全
-- =====================================================================

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40154, '变量目录查询', 401, 14, '', NULL, 'F', '0', '0',
   'price:variable:catalog', '#', 'admin', NOW(), '', NOW(),
   '公式编辑器 @ 下拉目录/后端 GET /variables/catalog'),
  (40155, '变量运维 CRUD', 401, 15, '', NULL, 'F', '0', '0',
   'price:variable:admin', '#', 'admin', NOW(), '', NOW(),
   '价格变量配置页 新增/更新/停用/后端 POST PUT DELETE /variables');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40154), (1, 40155),
  (10, 40154), (10, 40155),
  (11, 40154), (11, 40155);
