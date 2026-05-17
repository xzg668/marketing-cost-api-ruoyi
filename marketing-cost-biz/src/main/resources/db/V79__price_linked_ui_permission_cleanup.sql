-- =====================================================================
-- V79: 联动价 / 影响因素页面按钮权限整理
--
-- 目标：
--   1) 联动价格表只有一个主导入入口：导入月度联动价与影响因素 Excel。
--   2) 影响因素表第一期不开放单独补导，只保留查看、批次追溯、调价权限。
--   3) 给前端按钮和后端 @PreAuthorize 使用的权限点补齐 sys_menu F 行。
-- =====================================================================

SET NAMES utf8mb4;

-- V27 的旧命名偏“单独影响因素导入”，这里改成后续补导语义；第一期前端不展示该按钮。
UPDATE sys_menu
   SET menu_name = '影响因素补导',
       remark = '影响因素表 Excel 补导权限；第一期前端隐藏补导入口',
       update_time = NOW()
 WHERE perms = 'price:finance-base:import';

INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40158, '联动价查看', 401, 18, '', NULL, 'F', '0', '0',
   'price:linked-item:list', '#', 'admin', NOW(), '', NOW(),
   '联动价格表列表查看/后端 GET /api/v1/price-linked/items'),
  (40159, '联动价导入', 401, 19, '', NULL, 'F', '0', '0',
   'price:linked-item:import', '#', 'admin', NOW(), '', NOW(),
   '联动价格表主导入：导入月度联动价与影响因素 Excel'),
  (40160, '联动价导入历史查看', 401, 20, '', NULL, 'F', '0', '0',
   'price:linked-item:import-history:list', '#', 'admin', NOW(), '', NOW(),
   '联动价格表导入历史和自动绑定日志查看'),
  (40161, '联动价新增', 401, 21, '', NULL, 'F', '0', '0',
   'price:linked-item:add', '#', 'admin', NOW(), '', NOW(),
   '联动价格表手工新增'),
  (40162, '联动价编辑', 401, 22, '', NULL, 'F', '0', '0',
   'price:linked-item:edit', '#', 'admin', NOW(), '', NOW(),
   '联动价格表编辑和公式修改'),
  (40163, '联动价删除', 401, 23, '', NULL, 'F', '0', '0',
   'price:linked-item:remove', '#', 'admin', NOW(), '', NOW(),
   '联动价格表删除'),
  (40164, '影响因素查看', 401, 24, '', NULL, 'F', '0', '0',
   'price:finance-base:list', '#', 'admin', NOW(), '', NOW(),
   '影响因素表月度价格汇总查看'),
  (40165, '影响因素调价', 401, 25, '', NULL, 'F', '0', '0',
   'price:finance-base:edit', '#', 'admin', NOW(), '', NOW(),
   '影响因素表月度调价'),
  (40166, '影响因素批次查看', 401, 26, '', NULL, 'F', '0', '0',
   'price:finance-base:batch:list', '#', 'admin', NOW(), '', NOW(),
   '影响因素导入批次和来源行追溯');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40158), (1, 40159), (1, 40160), (1, 40161), (1, 40162), (1, 40163),
  (1, 40164), (1, 40165), (1, 40166),
  (10, 40158), (10, 40159), (10, 40160), (10, 40161), (10, 40162), (10, 40163),
  (10, 40164), (10, 40165), (10, 40166),
  (11, 40158), (11, 40159), (11, 40160), (11, 40161), (11, 40162), (11, 40163),
  (11, 40164), (11, 40165), (11, 40166);
