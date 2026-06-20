-- =============================================================================
-- V141: 报价产品 BOM 准备菜单重构
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 报价需求下的“报价单产品 BOM 处理”改名为“报价产品 BOM 准备”。
--   2. 新增标准化“BOM 数据管理 / BOM 明细”目录。
--   3. 将 U9 BOM、BOM 层级树、包装组件结构、BOM 结算明细放入 BOM 明细。
--   4. 将 BOM 明细过滤规则从基础数据迁到 BOM 数据管理。
--   5. 保留“基础数据 / U9基础数据 / 料品主档”不动。
--
-- 兼容策略：
--   - 不删除 sys_menu / sys_role_menu，避免既有角色授权断裂。
--   - 复用历史菜单 202、301、302，减少权限迁移成本。
-- =============================================================================

SET NAMES utf8mb4;

-- 报价需求目录保持报价接入定位。
UPDATE sys_menu
   SET menu_name = '报价需求',
       parent_id = 0,
       order_num = 1,
       path = 'ingest',
       component = NULL,
       menu_type = 'M',
       visible = '0',
       status = '0',
       icon = 'upload',
       update_time = NOW(),
       remark = '报价单接入、导入、报价产品 BOM 准备和接入流水'
 WHERE menu_id = 200;

-- 报价产品 BOM 准备入口：保留原 menu_id / path / component / perms，避免已有角色和前端路由断裂。
UPDATE sys_menu
   SET menu_name = '报价产品 BOM 准备',
       parent_id = 200,
       order_num = 3,
       path = 'quote-request-products/bom',
       component = 'ingest/quote-request-products/bom/index',
       menu_type = 'C',
       visible = '0',
       status = '0',
       perms = 'ingest:quote-product-bom:list',
       icon = 'tree',
       update_time = NOW(),
       remark = '报价产品 BOM 准备工作台：检查、复用、补录任务发起和财务确认入口'
 WHERE menu_id = 208;

-- 报价需求下只保留新接入链路。历史 OA / 电子图库 / 手工录入入口后续由 BOM 数据管理新入口承接。
UPDATE sys_menu
   SET visible = '1',
       update_time = NOW(),
       remark = CASE
         WHEN remark IS NULL OR remark = '' THEN 'V141：报价接入菜单重构后隐藏历史入口'
         ELSE CONCAT(remark, '；V141：报价接入菜单重构后隐藏历史入口')
       END
 WHERE parent_id = 200
   AND menu_id IN (201, 203, 204);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40461, 'BOM 数据管理', 0, 2, 'bom-data', NULL, 1, '0', 'M',
   '0', '0', NULL, 'tree-table', 'admin', NOW(), '', NOW(),
   'BOM 原始、层级、包装结构、结算明细、过滤规则和补录版本管理入口', NULL),
  (40462, 'BOM 明细', 40461, 1, 'details', NULL, 1, '0', 'M',
   '0', '0', NULL, 'tree', 'admin', NOW(), '', NOW(),
   'BOM 明细查看目录：原始数据、层级树、包装组件结构和结算明细', NULL)
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

-- 若历史库已被 V61 改出其他“BOM 数据管理”顶级目录，统一隐藏，避免侧边栏出现两个同名根目录。
UPDATE sys_menu
   SET visible = '1',
       update_time = NOW(),
       remark = CASE
         WHEN remark IS NULL OR remark = '' THEN 'V141：由标准 BOM 数据管理目录 40461 承接'
         ELSE CONCAT(remark, '；V141：由标准 BOM 数据管理目录 40461 承接')
       END
 WHERE parent_id = 0
   AND menu_id <> 40461
   AND menu_name = 'BOM 数据管理';

-- 历史 U9 BOM 明细入口迁入 BOM 明细目录。
UPDATE sys_menu
   SET menu_name = 'U9 BOM 原始数据',
       parent_id = 40462,
       order_num = 1,
       path = '/bom-data/details/u9-raw',
       component = 'pages:U9BomPage',
       menu_type = 'C',
       visible = '0',
       status = '0',
       perms = 'bom-data:u9-raw:list',
       icon = '#',
       update_time = NOW(),
       remark = 'U9 BOM 原始导入数据和构建入口'
 WHERE menu_id = 202;

-- 历史基础数据 / BOM 明细过滤规则迁入 BOM 数据管理。
UPDATE sys_menu
   SET menu_name = 'BOM 明细过滤规则',
       parent_id = 40461,
       order_num = 2,
       path = '/bom-data/filter-rules',
       component = 'pages:BomFilterRulePage',
       menu_type = 'C',
       visible = '0',
       status = '0',
       perms = 'bom-data:filter-rule:list',
       icon = '#',
       update_time = NOW(),
       remark = 'BOM 拍平结算行过滤规则配置'
 WHERE menu_id = 301;

-- 历史基础数据 / BOM 数据迁入 BOM 明细，明确为结算明细。
UPDATE sys_menu
   SET menu_name = 'BOM 结算明细',
       parent_id = 40462,
       order_num = 4,
       path = '/bom-data/details/costing-rows',
       component = 'pages:BomCostingRowPage',
       menu_type = 'C',
       visible = '0',
       status = '0',
       perms = 'bom-data:costing-row:list',
       icon = '#',
       update_time = NOW(),
       remark = '统一 BOM 过滤规则处理后的结算行明细'
 WHERE menu_id = 302;

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark,
   business_unit_type)
VALUES
  (40463, 'BOM 层级树查看', 40462, 2, '/bom-data/details/hierarchy-tree',
   'pages:BomTreeViewerPage', 1, '0', 'C',
   '0', '0', 'bom-data:hierarchy-tree:list', '#', 'admin', NOW(), '', NOW(),
   '按顶层料号查看 lp_bom_raw_hierarchy 层级树', NULL),
  (40464, '包装组件结构', 40462, 3, '/bom-data/details/package-structure',
   'pages:PackageComponentStructurePage', 1, '0', 'C',
   '0', '0', 'bom-data:package-structure:list', '#', 'admin', NOW(), '', NOW(),
   '按参考成品料号查看包装组件结构快照', NULL),
  (40467, 'BOM 补录任务', 40461, 3, '/bom-data/supplement-tasks',
   'pages:BomSupplementTaskPage', 1, '0', 'C',
   '0', '0', 'bom-data:supplement-task:list', '#', 'admin', NOW(), '', NOW(),
   '技术员 BOM 补录任务查看和财务审核入口', NULL),
  (40468, '补录 BOM 版本', 40461, 4, '/bom-data/supplement-versions',
   'pages:BomPreparationPlaceholderPage', 1, '0', 'C',
   '0', '0', 'bom-data:supplement-version:list', '#', 'admin', NOW(), '', NOW(),
   '已审核补录 BOM 版本和后续复用排查入口', NULL)
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

-- 默认业务角色授权。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40461), (1, 40462), (1, 40463), (1, 40464), (1, 40467), (1, 40468),
  (10, 40461), (10, 40462), (10, 40463), (10, 40464), (10, 40467), (10, 40468),
  (11, 40461), (11, 40462), (11, 40463), (11, 40464), (11, 40467), (11, 40468);

-- 兼容自定义角色：已有任一 BOM 明细能力的角色，补齐新的父目录。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40461
FROM sys_role_menu
WHERE menu_id IN (202, 301, 302, 40462, 40463, 40464, 40467, 40468);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40462
FROM sys_role_menu
WHERE menu_id IN (202, 302, 40463, 40464);

-- 兼容自定义角色：已有 BOM 数据管理根目录的角色，补齐新增查看入口。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40463
FROM sys_role_menu
WHERE menu_id = 40461;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40464
FROM sys_role_menu
WHERE menu_id = 40461;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40467
FROM sys_role_menu
WHERE menu_id = 40461;

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40468
FROM sys_role_menu
WHERE menu_id = 40461;

-- V141 结束
