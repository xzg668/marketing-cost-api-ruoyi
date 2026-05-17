-- =============================================================================
-- V65: CMS 成本数据前端菜单
--
-- 目的：
--   1. 在“基础数据”下新增“CMS 成本数据”二级菜单。
--   2. 提供导入、三类原始数据、公共生效来源页面入口。
--   3. 不移动、不覆盖既有工资表和辅料管理入口。
-- =============================================================================

SET NAMES utf8mb4;

-- 兼容历史库：当前动态菜单使用 40159 作为“基础数据”；旧 V5 初始化库只有 300。
-- 前端 Sidebar 已隐藏旧 300，所以缺少 40159 时先补一个可展示的基础数据根菜单。
INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40159, '基础数据', 0, 20, 'base', NULL, 1, '0', 'M',
   '0', '0', NULL, 'database', 'admin', NOW(), '', NOW(),
   'V65：CMS成本数据菜单所需基础数据根菜单', NULL)
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
  update_time = NOW();

-- 40232 不再作为独立用户菜单；cms_cost_import_batch 仅保留为底层入库记录表。
DELETE FROM sys_role_menu WHERE menu_id = 40232;
DELETE FROM sys_menu WHERE menu_id = 40232;

-- 40236 旧“CMS 辅料科目配置”菜单已废弃；辅料/辅助人工取数改由 CMS 科目设置字典决定。
DELETE FROM sys_role_menu WHERE menu_id = 40236;
DELETE FROM sys_menu WHERE menu_id = 40236;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40230, 'CMS 成本数据', 40159, 14, '/base/cms-cost', NULL, 1, '0', 'M',
   '0', '0', NULL, 'FolderOpened', 'admin', NOW(), '', NOW(),
   'CMS成本数据导入、原始数据追溯和配置入口', NULL),
  (40231, 'CMS 导入', 40230, 1, '/base/cms-cost/import', 'pages:CmsCostImportPage', 1, '0', 'C',
   '0', '0', 'cms:cost:import', 'Upload', 'admin', NOW(), '', NOW(),
   'CMS三类来源文件导入入口', NULL),
  (40233, '计划成本原始数据', 40230, 11, '/base/cms-cost/plan-rows', 'pages:CmsPlanCostRawPage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Document', 'admin', NOW(), '', NOW(),
   'CMS产品计划成本汇总原始数据查询入口', NULL),
  (40234, '车间料工费原始数据', 40230, 12, '/base/cms-cost/workshop-rows', 'pages:CmsWorkshopLaborRawPage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Coin', 'admin', NOW(), '', NOW(),
   'CMS产品车间料工费汇总原始数据查询入口', NULL),
  (40235, '科目成本原始数据', 40230, 13, '/base/cms-cost/subject-rows', 'pages:CmsProductSubjectCostRawPage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Files', 'admin', NOW(), '', NOW(),
   'CMS产品科目成本汇总原始数据查询入口', NULL),
  (40237, 'CMS 公共生效来源', 40230, 2, '/base/cms-cost/effective-sources', 'pages:CmsCostEffectiveSourcePage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Stamp', 'admin', NOW(), '', NOW(),
   'CMS料号年度成本公共生效来源维护入口', NULL),
  (40238, '公共生效来源刷新', 40237, 1, '', NULL, 1, '0', 'F',
   '0', '0', 'cms:cost:effective:refresh', '#', 'admin', NOW(), '', NOW(),
   'CMS公共生效来源默认生成和期间刷新权限', NULL)
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
  (1, 40159), (1, 40230), (1, 40231), (1, 40233), (1, 40234), (1, 40235), (1, 40237), (1, 40238),
  (10, 40159), (10, 40230), (10, 40231), (10, 40233), (10, 40234), (10, 40235), (10, 40237), (10, 40238),
  (11, 40159), (11, 40230), (11, 40231), (11, 40233), (11, 40234), (11, 40235), (11, 40237), (11, 40238);

-- 兼容已存在的自定义角色：如果角色已经能看到 CMS 成本数据，
-- 自动补齐公共生效来源页面和刷新按钮权限，避免固定 role_id 种子遗漏运行账号。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40237
FROM sys_role_menu
WHERE menu_id IN (40230);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40238
FROM sys_role_menu
WHERE menu_id IN (40230, 40237);
