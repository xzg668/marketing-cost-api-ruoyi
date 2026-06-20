-- =============================================================================
-- V60  报价单接入枚举字典、菜单权限、分类规则种子                 2026-05-11
--
-- 本脚本只固化 T2 基础数据：
--   1) 接入模块统一枚举字典
--   2) 报价需求下的 报价单导入 / 报价单接入 / 报价单产品 BOM 处理 / 接入流水 菜单
--   3) 按钮权限点
--   4) 分类规则兜底重放
--
-- 兼容策略：
--   - 不删除旧 OA/BOM 菜单；旧入口仅调整到新接入菜单之后，避免 T2 阶段破坏现有页面。
--   - T9 前端路由落地时再做旧入口跳转或收敛。
-- =============================================================================

SET NAMES utf8mb4;

-- -----------------------------------------------------------------------------
-- 1) 枚举字典
-- -----------------------------------------------------------------------------

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价单来源类型', 'quote_source_type', '0', '报价单接入来源类型'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_source_type');
UPDATE sys_dict_type SET dict_name='报价单来源类型'
 WHERE dict_type='quote_source_type' AND dict_name<>'报价单来源类型';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价场景', 'quote_scenario', '0', '报价单接入归一化报价场景'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_scenario');
UPDATE sys_dict_type SET dict_name='报价场景'
 WHERE dict_type='quote_scenario' AND dict_name<>'报价场景';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价单接入状态', 'quote_ingest_status', '0', '接入流水处理状态'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_ingest_status');
UPDATE sys_dict_type SET dict_name='报价单接入状态'
 WHERE dict_type='quote_ingest_status' AND dict_name<>'报价单接入状态';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价单分类状态', 'quote_classification_status', '0', '单据/行级分类确认状态'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_classification_status');
UPDATE sys_dict_type SET dict_name='报价单分类状态'
 WHERE dict_type='quote_classification_status' AND dict_name<>'报价单分类状态';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价单 BOM 状态', 'quote_bom_status', '0', '报价单产品行 BOM 可用性状态'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_bom_status');
UPDATE sys_dict_type SET dict_name='报价单 BOM 状态'
 WHERE dict_type='quote_bom_status' AND dict_name<>'报价单 BOM 状态';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价额外费用分类', 'quote_fee_category', '0', '新品/衍生品等额外费用分类'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_fee_category');
UPDATE sys_dict_type SET dict_name='报价额外费用分类'
 WHERE dict_type='quote_fee_category' AND dict_name<>'报价额外费用分类';

INSERT INTO sys_dict_type (dict_name, dict_type, status, remark)
  SELECT '报价回写状态', 'quote_writeback_status', '0', '外部系统回写任务状态'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_type='quote_writeback_status');
UPDATE sys_dict_type SET dict_name='报价回写状态'
 WHERE dict_type='quote_writeback_status' AND dict_name<>'报价回写状态';

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '真实 OA 推送', 'OA', 'quote_source_type', '0', '后续 OA 系统真实推送'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='OA');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '模拟 OA 推送', 'MOCK_OA', 'quote_source_type', '0', '当前阶段平台内模拟推送'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='MOCK_OA');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '平台手工录入', 'MANUAL', 'quote_source_type', '0', '平台页面手工录入'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='MANUAL');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, 'Excel 导入', 'EXCEL', 'quote_source_type', '0', '财务人员 Excel 导入'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='EXCEL');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '技术补充格式', 'TECH', 'quote_source_type', '0', '后续技术端补充格式'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='TECH');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 6, '历史存量数据', 'LEGACY', 'quote_source_type', '0', '迁移前已存在的 OA/报价数据'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_source_type' AND dict_value='LEGACY');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '板换直销', 'DIRECT_SALE', 'quote_scenario', '0', 'FI-SC-020'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='DIRECT_SALE');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '标准品/批量品', 'STANDARD_BATCH', 'quote_scenario', '0', 'FI-SC-006'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='STANDARD_BATCH');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '新品', 'NEW_PRODUCT', 'quote_scenario', '0', 'FI-SC-005 / FI-SR-005'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='NEW_PRODUCT');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '批量品', 'MASS_PRODUCT', 'quote_scenario', '0', 'FI-SR-005'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='MASS_PRODUCT');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '衍生品', 'DERIVED_PRODUCT', 'quote_scenario', '0', 'FI-SR-005'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='DERIVED_PRODUCT');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 6, '技术补充单', 'TECH_SUPPLEMENT', 'quote_scenario', '0', '技术格式'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='TECH_SUPPLEMENT');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 99, '待人工确认', 'UNKNOWN', 'quote_scenario', '0', '无法自动识别'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_scenario' AND dict_value='UNKNOWN');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '已收到', 'RECEIVED', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='RECEIVED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '校验中', 'VALIDATING', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='VALIDATING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '校验失败', 'REJECTED', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='REJECTED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '分类待确认', 'CLASSIFY_PENDING', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='CLASSIFY_PENDING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '已导入', 'IMPORTED', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='IMPORTED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 6, '系统异常', 'FAILED', 'quote_ingest_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_ingest_status' AND dict_value='FAILED');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '已确认', 'CONFIRMED', 'quote_classification_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_classification_status' AND dict_value='CONFIRMED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '待确认', 'PENDING', 'quote_classification_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_classification_status' AND dict_value='PENDING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '已驳回', 'REJECTED', 'quote_classification_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_classification_status' AND dict_value='REJECTED');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '未检查', 'NOT_CHECKED', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='NOT_CHECKED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '已同步', 'SYNCED', 'quote_bom_status', '0', '已从本地正式 BOM 或有效补录 BOM 匹配'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='SYNCED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, 'BOM 当月发起过报价', 'CURRENT_MONTH_QUOTED', 'quote_bom_status', '0', '当月 lp_bom_costing_row 已存在该产品料号报价核算数据'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='CURRENT_MONTH_QUOTED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, 'U9 有此 BOM', 'U9_BOM_EXISTS', 'quote_bom_status', '0', 'lp_bom_raw_hierarchy 已存在该产品料号 BOM'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='U9_BOM_EXISTS');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '无 BOM', 'NO_BOM', 'quote_bom_status', '0', '无可用 BOM，待发起补录'
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='NO_BOM');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 6, '待技术补录', 'ENTRY_PENDING', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='ENTRY_PENDING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 7, '技术录入中', 'ENTRY_IN_PROGRESS', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='ENTRY_IN_PROGRESS');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 8, '已手工录入', 'MANUAL_ENTERED', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='MANUAL_ENTERED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 9, '手工 BOM 已过期', 'EXPIRED', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='EXPIRED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 10, '检查异常', 'CHECK_FAILED', 'quote_bom_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_bom_status' AND dict_value='CHECK_FAILED');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '工装夹具', 'TOOLING', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='TOOLING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '模具', 'MOLD', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='MOLD');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '认证', 'CERTIFICATION', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='CERTIFICATION');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '设备', 'EQUIPMENT', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='EQUIPMENT');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 5, '刀具', 'CUTTER', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='CUTTER');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 6, '人工', 'LABOR', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='LABOR');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 7, '报废', 'SCRAP', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='SCRAP');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 8, '其他', 'OTHER', 'quote_fee_category', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_fee_category' AND dict_value='OTHER');

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 1, '待回写', 'PENDING', 'quote_writeback_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_writeback_status' AND dict_value='PENDING');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 2, '已回写', 'SUCCESS', 'quote_writeback_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_writeback_status' AND dict_value='SUCCESS');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 3, '回写失败', 'FAILED', 'quote_writeback_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_writeback_status' AND dict_value='FAILED');
INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark)
  SELECT 4, '已跳过', 'SKIPPED', 'quote_writeback_status', '0', NULL
  FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_dict_data WHERE dict_type='quote_writeback_status' AND dict_value='SKIPPED');

-- -----------------------------------------------------------------------------
-- 2) 报价需求菜单与权限点
-- -----------------------------------------------------------------------------

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (200, '报价需求', 0, 1, 'ingest', NULL, 'M', '0', '0',
   NULL, 'upload', 'admin', NOW(), '', NOW(), '报价需求目录')
ON DUPLICATE KEY UPDATE
  menu_name='报价需求', parent_id=0, order_num=1, path='ingest',
  component=NULL, menu_type='M', visible='0', status='0', perms=NULL,
  icon='upload', update_time=NOW(), remark='报价需求目录';

-- 旧入口保留，但排到新报价单接入菜单之后，T9 再统一处理跳转或收敛。
UPDATE sys_menu
   SET order_num = CASE menu_id
       WHEN 201 THEN 90
       WHEN 202 THEN 91
       WHEN 203 THEN 92
       WHEN 204 THEN 93
       ELSE order_num
     END,
     update_time = NOW(),
     remark = CASE
       WHEN remark IS NULL OR remark = '' THEN 'T2 保留旧入口，后续 T9 统一收敛'
       ELSE remark
     END
 WHERE parent_id = 200
   AND menu_id IN (201, 202, 203, 204);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (205, '报价单导入', 200, 1, 'quote-requests/import', 'ingest/quote-requests/import/index', 'C', '0', '0',
   'ingest:quote:import', 'upload', 'admin', NOW(), '', NOW(), '财务 Excel 导入入口'),
  (206, '报价单接入', 200, 2, 'quote-requests', 'ingest/quote-requests/index', 'C', '0', '0',
   'ingest:quote:list', 'form', 'admin', NOW(), '', NOW(), '报价单接入列表，单据维度工作台'),
  (208, '报价单产品 BOM 处理', 200, 3, 'quote-request-products/bom', 'ingest/quote-request-products/bom/index', 'C', '0', '0',
   'ingest:quote-product-bom:list', 'tree', 'admin', NOW(), '', NOW(), '报价单产品行 BOM 批量同步与补录任务入口'),
  (207, '接入流水', 200, 4, 'quote-ingest-logs', 'ingest/quote-ingest-logs/index', 'C', '0', '0',
   'ingest:quote-log:list', 'log', 'admin', NOW(), '', NOW(), '接入流水审计与排错')
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name),
  parent_id=VALUES(parent_id),
  order_num=VALUES(order_num),
  path=VALUES(path),
  component=VALUES(component),
  menu_type=VALUES(menu_type),
  visible=VALUES(visible),
  status=VALUES(status),
  perms=VALUES(perms),
  icon=VALUES(icon),
  update_time=NOW(),
  remark=VALUES(remark);

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (2061, '确认分类', 206, 1, '', NULL, 'F', '0', '0',
   'ingest:quote:confirm', '#', 'admin', NOW(), '', NOW(), '报价单接入工作台分类确认'),
  (2062, '检查 BOM', 206, 2, '', NULL, 'F', '0', '0',
   'ingest:quote:bom-check', '#', 'admin', NOW(), '', NOW(), '报价单产品行 BOM 状态检查'),
  (2063, '查看接入原文', 206, 3, '', NULL, 'F', '0', '0',
   'ingest:quote:raw', '#', 'admin', NOW(), '', NOW(), '查看原始 payload / normalized payload'),
  (2064, '模拟 OA 推送', 206, 4, '', NULL, 'F', '0', '0',
   'ingest:quote:mock-create', '#', 'admin', NOW(), '', NOW(), '仅实施/管理员使用的模拟推送按钮')
ON DUPLICATE KEY UPDATE
  menu_name=VALUES(menu_name),
  parent_id=VALUES(parent_id),
  order_num=VALUES(order_num),
  path=VALUES(path),
  component=VALUES(component),
  menu_type=VALUES(menu_type),
  visible=VALUES(visible),
  status=VALUES(status),
  perms=VALUES(perms),
  icon=VALUES(icon),
  update_time=NOW(),
  remark=VALUES(remark);

-- 菜单和普通按钮给 admin / BU_DIRECTOR / BU_STAFF；模拟推送只给 admin。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 205), (1, 206), (1, 207), (1, 208), (1, 2061), (1, 2062), (1, 2063), (1, 2064),
  (10, 205), (10, 206), (10, 207), (10, 208), (10, 2061), (10, 2062), (10, 2063),
  (11, 205), (11, 206), (11, 207), (11, 208), (11, 2061), (11, 2062), (11, 2063);

-- -----------------------------------------------------------------------------
-- 3) 分类规则兜底重放
-- -----------------------------------------------------------------------------

INSERT INTO lp_quote_ingest_type_rule
  (rule_code, rule_name, priority, process_code, business_type_keyword,
   target_business_unit_type, target_quote_scenario, confidence, remark)
VALUES
  ('RULE_FI_SC_020', 'FI-SC-020 板换直销', 10, 'FI-SC-020', NULL,
   'COMMERCIAL', 'DIRECT_SALE', 100, '流程编号可直接识别'),
  ('RULE_FI_SC_006', 'FI-SC-006 标准品/批量品', 20, 'FI-SC-006', NULL,
   'COMMERCIAL', 'STANDARD_BATCH', 100, '流程编号可直接识别'),
  ('RULE_FI_SC_005', 'FI-SC-005 商用新品', 30, 'FI-SC-005', NULL,
   'COMMERCIAL', 'NEW_PRODUCT', 100, '流程编号可直接识别'),
  ('RULE_FI_SR_005_NEW', 'FI-SR-005 新品', 40, 'FI-SR-005', '新品',
   'HOUSEHOLD', 'NEW_PRODUCT', 100, '需业务类型辅助识别'),
  ('RULE_FI_SR_005_MASS', 'FI-SR-005 批量品', 50, 'FI-SR-005', '批量',
   'HOUSEHOLD', 'MASS_PRODUCT', 100, '需业务类型辅助识别'),
  ('RULE_FI_SR_005_DERIVED', 'FI-SR-005 衍生品', 60, 'FI-SR-005', '衍生',
   'HOUSEHOLD', 'DERIVED_PRODUCT', 100, '需业务类型辅助识别'),
  ('RULE_TECH_MANUAL', '技术补充格式', 70, 'TECH_MANUAL', NULL,
   'UNKNOWN', 'TECH_SUPPLEMENT', 60, '格式未定，默认进入待确认')
ON DUPLICATE KEY UPDATE
  rule_name = VALUES(rule_name),
  priority = VALUES(priority),
  process_code = VALUES(process_code),
  business_type_keyword = VALUES(business_type_keyword),
  target_business_unit_type = VALUES(target_business_unit_type),
  target_quote_scenario = VALUES(target_quote_scenario),
  confidence = VALUES(confidence),
  remark = VALUES(remark),
  updated_at = CURRENT_TIMESTAMP;

-- V60 结束
