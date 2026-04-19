-- ============================================================
-- V5: 若依权限初始化数据（角色 + 菜单 + 角色菜单关联）
-- 依赖: V4__ruoyi_permission_tables.sql 已执行
-- 幂等: 使用 INSERT IGNORE，重复执行不报错不覆盖
-- v1.3 修订：菜单结构与前端 src/menu.js 1:1 对齐，新增"系统管理"目录
--           - business_unit_type 全部置 NULL（当前系统未做商/家用菜单区分）
--             未来若需限制某条菜单仅某业务单元可见，修改该字段即可
-- ============================================================

-- --------------------------------------------------------
-- 1. 角色数据（4 条）
-- --------------------------------------------------------

-- 更新 V3 种子的 admin 角色
-- v1.3: role_name 改为"系统管理员"（方案 C：admin 也受业务单元约束，不再是"超级"）
UPDATE sys_role
SET role_key    = 'admin',
    role_name   = '系统管理员',
    data_scope  = '2',  -- 按业务单元隔离（与 BU_DIRECTOR 一致）
    create_time = IFNULL(create_time, NOW()),
    update_time = NOW(),
    remark      = '管理本单元用户 + 全局系统配置（菜单/字典/部门/岗位）；可分配任意角色（含 admin）'
WHERE role_id = 1;

-- 软删除旧的已废弃角色（v1.1 的 commercial_quoter / household_quoter / finance_reviewer / 旧 oa_collaborator）
UPDATE sys_role SET del_flag = '2' WHERE role_id IN (2, 3, 4, 5) AND del_flag = '0';

-- 插入新角色（role_id 从 10 开始，避免与旧数据冲突）
INSERT IGNORE INTO sys_role (role_id, role_name, role_key, role_sort, data_scope, status, del_flag, create_by, create_time, update_by, update_time, remark)
VALUES
(10, '业务单元总监', 'bu_director',     2, '2', '0', '0', 'admin', NOW(), '', NOW(), '本业务单元全部操作 + 本单元用户/角色管理'),
(11, '业务单元人员', 'bu_staff',        3, '2', '0', '0', 'admin', NOW(), '', NOW(), '本业务单元全部操作（报价/财务/费率等）'),
(12, 'OA协作者',     'oa_collaborator', 4, '5', '0', '0', 'admin', NOW(), '', NOW(), '仅关联报价单数据，受限页面，可与其他角色叠加');
-- data_scope: 1=全部 2=按业务单元(用户business_unit_type) 5=自定义(按oa_no)

-- --------------------------------------------------------
-- 2. 菜单数据
-- menu_type: M=目录, C=菜单, F=按钮
-- business_unit_type: NULL=通用（当前全部置 NULL，后续按业务再细分）
-- menu_id 分段：100系统 / 200数据接入 / 300基础数据 / 400价格源 / 500成本核算 / 600数据分析 / 700结账
-- --------------------------------------------------------

-- ==================== 系统管理（新增目录） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(100, '系统管理', 0, 10, 'system', NULL, 'M', '0', '0', NULL, 'setting', 'admin', NOW(), '', NOW(), '系统管理目录', NULL),
(101, '用户管理', 100, 1, 'user',  'system/user/index',  'C', '0', '0', 'system:user:list', 'user',      'admin', NOW(), '', NOW(), '', NULL),
(102, '角色管理', 100, 2, 'role',  'system/role/index',  'C', '0', '0', 'system:role:list', 'peoples',   'admin', NOW(), '', NOW(), '', NULL),
(103, '菜单管理', 100, 3, 'menu',  'system/menu/index',  'C', '0', '0', 'system:menu:list', 'tree-table','admin', NOW(), '', NOW(), '', NULL),
(104, '部门管理', 100, 4, 'dept',  'system/dept/index',  'C', '0', '0', 'system:dept:list', 'tree',      'admin', NOW(), '', NOW(), '', NULL),
(105, '岗位管理', 100, 5, 'post',  'system/post/index',  'C', '0', '0', 'system:post:list', 'post',      'admin', NOW(), '', NOW(), '', NULL),
(106, '字典管理', 100, 6, 'dict',  'system/dict/index',  'C', '0', '0', 'system:dict:list', 'dict',      'admin', NOW(), '', NOW(), '', NULL),
-- 用户管理按钮级权限
(1011, '用户查询', 101, 1, '', NULL, 'F', '0', '0', 'system:user:query',    '#', 'admin', NOW(), '', NOW(), '', NULL),
(1012, '用户新增', 101, 2, '', NULL, 'F', '0', '0', 'system:user:add',      '#', 'admin', NOW(), '', NOW(), '', NULL),
(1013, '用户修改', 101, 3, '', NULL, 'F', '0', '0', 'system:user:edit',     '#', 'admin', NOW(), '', NOW(), '', NULL),
(1014, '用户删除', 101, 4, '', NULL, 'F', '0', '0', 'system:user:remove',   '#', 'admin', NOW(), '', NOW(), '', NULL);

-- ==================== 数据接入（对应 menu.js 的 ingest 分组） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(200, '数据接入', 0, 1, 'ingest', NULL, 'M', '0', '0', NULL, 'upload', 'admin', NOW(), '', NOW(), '数据接入目录', NULL),
(201, 'OA报价单',         200, 1, 'oa-form', 'ingest/oa-form/index', 'C', '0', '0', 'ingest:oa-form:list',  'form',      'admin', NOW(), '', NOW(), '', NULL),
(202, 'U9 BOM明细',       200, 2, 'u9Bom',   'ingest/u9Bom/index',   'C', '0', '0', 'ingest:u9-bom:list',   'component', 'admin', NOW(), '', NOW(), '', NULL),
(203, '电子图库BOM明细',   200, 3, 'eleDraw', 'ingest/eleDraw/index', 'C', '0', '0', 'ingest:ele-draw:list', 'component', 'admin', NOW(), '', NOW(), '', NULL),
(204, 'BOM明细录入',       200, 4, 'addbom',  'ingest/addbom/index',  'C', '0', '0', 'ingest:addbom:list',   'edit',      'admin', NOW(), '', NOW(), '', NULL);

-- ==================== 基础数据（对应 menu.js 的 base 分组，含费率/辅料） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(300, '基础数据', 0, 2, 'base', NULL, 'M', '0', '0', NULL, 'database', 'admin', NOW(), '', NOW(), '基础数据目录', NULL),
(301, 'BOM明细过滤规则',    300, 1, 'bomfilter',         'base/bomfilter/index',         'C', '0', '0', 'base:bomfilter:list',         'filter',       'admin', NOW(), '', NOW(), '', NULL),
(302, 'BOM数据',            300, 2, 'material',          'base/material/index',          'C', '0', '0', 'base:material:list',          'tree-table',   'admin', NOW(), '', NOW(), '', NULL),
(303, '物料价格类型对照表',  300, 3, 'map',               'base/map/index',               'C', '0', '0', 'base:map:list',               'money',        'admin', NOW(), '', NOW(), '', NULL),
(304, '部门经费率对照表',    300, 4, 'fixed',             'base/fixed/index',             'C', '0', '0', 'base:fixed:list',             'peoples',      'admin', NOW(), '', NOW(), '', NULL),
-- 辅料管理（二级目录）
(305, '辅料管理',            300, 5, 'aux', NULL, 'M', '0', '0', NULL, 'slider', 'admin', NOW(), '', NOW(), '辅料管理（含辅料价格表/辅料上浮比率表）', NULL),
(3051, '辅料价格表',         305, 1, 'subject',           'base/aux/subject/index',       'C', '0', '0', 'base:aux:subject:list',       'money',        'admin', NOW(), '', NOW(), '', NULL),
(3052, '辅料上浮比率表',      305, 2, 'item',              'base/aux/item/index',          'C', '0', '0', 'base:aux:item:list',          'rate',         'admin', NOW(), '', NOW(), '', NULL),
(306, '物料表',              300, 6, 'materweight',       'base/materweight/index',       'C', '0', '0', 'base:materweight:list',       'component',    'admin', NOW(), '', NOW(), '', NULL),
(307, '工资表',              300, 7, 'salary',            'base/salary/index',            'C', '0', '0', 'base:salary:list',            'money',        'admin', NOW(), '', NOW(), '', NULL),
(308, '质量损失率对照表',    300, 8, 'quantityLoss',      'base/quantityLoss/index',      'C', '0', '0', 'base:quantityLoss:list',      'bug',          'admin', NOW(), '', NOW(), '', NULL),
(309, '制造费用率对照表',    300, 9, 'manufactureRate',   'base/manufactureRate/index',   'C', '0', '0', 'base:manufactureRate:list',   'tool',         'admin', NOW(), '', NOW(), '', NULL),
(310, '三项费用费率对照表',  300, 10,'threeExpenseRate',  'base/threeExpenseRate/index',  'C', '0', '0', 'base:threeExpenseRate:list',  'money',        'admin', NOW(), '', NOW(), '', NULL),
(311, '产品属性对照表',      300, 11,'productProperty',   'base/productProperty/index',   'C', '0', '0', 'base:productProperty:list',   'star',         'admin', NOW(), '', NOW(), '', NULL),
(312, '其他费用率对照表',    300, 12,'other',             'base/other/index',             'C', '0', '0', 'base:other:list',             'documentation','admin', NOW(), '', NOW(), '', NULL);

-- ==================== 价格源管理（对应 menu.js 的 price 分组） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(400, '价格源管理', 0, 3, 'price', NULL, 'M', '0', '0', NULL, 'shopping', 'admin', NOW(), '', NOW(), '价格源管理目录', NULL),
-- 联动价（二级目录）
(401, '联动价', 400, 1, 'linked', NULL, 'M', '0', '0', NULL, 'link', 'admin', NOW(), '', NOW(), '联动价管理（含4个子项）', NULL),
(4011, '联动价格表',    401, 1, 'result',       'price/linked/result/index',       'C', '0', '0', 'price:linked:result:list',       'list',          'admin', NOW(), '', NOW(), '', NULL),
(4012, '联动价计算',    401, 2, 'oa-result',    'price/linked/oa-result/index',    'C', '0', '0', 'price:linked:oa-result:list',    'play',          'admin', NOW(), '', NOW(), '', NULL),
(4013, '公式配置',      401, 3, 'formula',      'price/linked/formula/index',      'C', '0', '0', 'price:linked:formula:list',      'edit',          'admin', NOW(), '', NOW(), '', NULL),
(4014, '影响因素表',    401, 4, 'finance-base', 'price/linked/finance-base/index', 'C', '0', '0', 'price:linked:finance-base:list', 'documentation', 'admin', NOW(), '', NOW(), '', NULL),
(402, '区间价',         400, 2, 'range',             'price/range/index',             'C', '0', '0', 'price:range:list',             'slider',        'admin', NOW(), '', NOW(), '', NULL),
(403, '结算价',         400, 3, 'settle',            'price/settle/index',            'C', '0', '0', 'price:settle:list',            'money',         'admin', NOW(), '', NOW(), '', NULL),
(404, '固定价',         400, 4, 'fixed',             'price/fixed/index',             'C', '0', '0', 'price:fixed:list',             'documentation', 'admin', NOW(), '', NOW(), '', NULL),
(405, '委外结算价表',   400, 5, 'outsource_settle',  'price/outsource_settle/index',  'C', '0', '0', 'price:outsource-settle:list',  'money',         'admin', NOW(), '', NOW(), '', NULL),
(406, '委外固定价表',   400, 6, 'outsource_fixed',   'price/outsource_fixed/index',   'C', '0', '0', 'price:outsource-fixed:list',   'documentation', 'admin', NOW(), '', NOW(), '', NULL);

-- ==================== 成本核算（对应 menu.js 的 cost 分组） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(500, '成本核算', 0, 4, 'cost', NULL, 'M', '0', '0', NULL, 'chart', 'admin', NOW(), '', NOW(), '成本核算目录', NULL),
(501, '实时成本计算',   500, 1, 'run',           'cost/run/index',           'C', '0', '0', 'cost:run:list',           'play',      'admin', NOW(), '', NOW(), '', NULL),
(502, '已核算成本明细', 500, 2, 'run/completed', 'cost/run/completed/index', 'C', '0', '0', 'cost:run:completed:list', 'clipboard', 'admin', NOW(), '', NOW(), '', NULL);

-- ==================== 数据分析（对应 menu.js 的 analysis 分组） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(600, '数据分析', 0, 5, 'analysis', NULL, 'M', '0', '0', NULL, 'chart', 'admin', NOW(), '', NOW(), '数据分析目录', NULL),
(601, '成本分析', 600, 1, 'cost',   'analysis/cost/index',   'C', '0', '0', 'analysis:cost:list',   'chart', 'admin', NOW(), '', NOW(), '', NULL),
(602, '报表',     600, 2, 'report', 'analysis/report/index', 'C', '0', '0', 'analysis:report:list', 'list',  'admin', NOW(), '', NOW(), '', NULL);

-- ==================== 结账（对应 menu.js 的 settlement 分组） ====================
INSERT IGNORE INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status, perms, icon, create_by, create_time, update_by, update_time, remark, business_unit_type)
VALUES
(700, '结账', 0, 6, 'settlement', NULL, 'M', '0', '0', NULL, 'lock', 'admin', NOW(), '', NOW(), '结账目录', NULL),
(701, '月度调价', 700, 1, 'monthly-adjustment', 'settlement/monthly-adjustment/index', 'C', '0', '0', 'settlement:monthly-adjustment:list', 'money', 'admin', NOW(), '', NOW(), '', NULL);

-- --------------------------------------------------------
-- 3. 业务单元字典数据（供登录页下拉选择，未来可在字典管理页面扩展）
-- --------------------------------------------------------
INSERT IGNORE INTO sys_dict_type (dict_id, dict_name, dict_type, status, deleted, created_at, updated_at)
VALUES (1, '业务单元类型', 'biz_unit_type', '0', 0, NOW(), NOW());

INSERT IGNORE INTO sys_dict_data (dict_code, dict_sort, dict_label, dict_value, dict_type, is_default, status, deleted, created_at, updated_at)
VALUES
(1, 1, '商用', 'COMMERCIAL', 'biz_unit_type', 'N', '0', 0, NOW(), NOW()),
(2, 2, '家用', 'HOUSEHOLD',  'biz_unit_type', 'N', '0', 0, NOW(), NOW());

-- --------------------------------------------------------
-- 4. 角色菜单关联
-- 策略：
--   admin          → 全部菜单
--   BU_DIRECTOR    → 系统管理目录 + 用户管理(含按钮) + 全部业务菜单
--   BU_STAFF       → 全部业务菜单（menu_id >= 200，不含系统管理）
--   OA_COLLABORATOR→ 无菜单（通过协作 Token 访问受限页面）
-- --------------------------------------------------------

-- 4.1 admin（role_id=1）关联全部菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 1, menu_id FROM sys_menu;

-- 4.2 BU_DIRECTOR（role_id=10）关联系统管理(仅用户管理) + 全部业务菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 10, menu_id FROM sys_menu
WHERE menu_id IN (
    -- 系统管理目录 + 用户管理(含按钮)
    100, 101, 1011, 1012, 1013, 1014
)
OR menu_id >= 200;

-- 4.3 BU_STAFF（role_id=11）关联全部业务菜单
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT 11, menu_id FROM sys_menu
WHERE menu_id >= 200;

-- 4.4 OA_COLLABORATOR（role_id=12）不关联任何菜单，留空
