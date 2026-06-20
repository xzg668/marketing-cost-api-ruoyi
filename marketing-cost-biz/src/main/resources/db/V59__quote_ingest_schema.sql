-- =============================================================================
-- V59: 报价单接入数据模型
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 兼容扩展 oa_form / oa_form_item，不重建、不清空历史数据。
--   2. 新增报价单接入流水、分类规则、扩展字段、额外费用、BOM 状态投影、回写任务表。
--   3. 对历史 oa_form/oa_form_item 回填接入默认状态。
--
-- 约束：
--   - calc_at 在部分库中已经存在，必须先判断字段是否存在。
--   - 字段和索引通过 INFORMATION_SCHEMA 判断后再添加，支持已有库补丁式执行。
-- =============================================================================

DROP PROCEDURE IF EXISTS _quote_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _quote_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE _quote_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @quote_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_definition);
    PREPARE quote_add_column_stmt FROM @quote_add_column_sql;
    EXECUTE quote_add_column_stmt;
    DEALLOCATE PREPARE quote_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _quote_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @quote_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE quote_add_index_stmt FROM @quote_add_index_sql;
    EXECUTE quote_add_index_stmt;
    DEALLOCATE PREPARE quote_add_index_stmt;
  END IF;
END //

DELIMITER ;

-- -----------------------------------------------------------------------------
-- oa_form 扩展
-- -----------------------------------------------------------------------------

CALL _quote_add_column_if_not_exists('oa_form', 'source_type',
  'source_type VARCHAR(32) DEFAULT NULL COMMENT ''接入来源：OA/MOCK_OA/MANUAL/EXCEL/TECH/LEGACY'' AFTER id');
CALL _quote_add_column_if_not_exists('oa_form', 'source_system',
  'source_system VARCHAR(64) DEFAULT NULL COMMENT ''来源系统，如 OA、marketing-cost、tech-portal'' AFTER source_type');
CALL _quote_add_column_if_not_exists('oa_form', 'external_form_no',
  'external_form_no VARCHAR(128) DEFAULT NULL COMMENT ''外部系统原始单号'' AFTER source_system');
CALL _quote_add_column_if_not_exists('oa_form', 'process_code',
  'process_code VARCHAR(64) DEFAULT NULL COMMENT ''流程编号，如 FI-SC-006、FI-SR-005'' AFTER external_form_no');
CALL _quote_add_column_if_not_exists('oa_form', 'process_name',
  'process_name VARCHAR(255) DEFAULT NULL COMMENT ''流程名称'' AFTER process_code');
CALL _quote_add_column_if_not_exists('oa_form', 'quote_scenario',
  'quote_scenario VARCHAR(64) DEFAULT NULL COMMENT ''报价场景：DIRECT_SALE/STANDARD_BATCH/NEW_PRODUCT/MASS_PRODUCT/DERIVED_PRODUCT/TECH_SUPPLEMENT/UNKNOWN'' AFTER process_name');
CALL _quote_add_column_if_not_exists('oa_form', 'applicant_dept',
  'applicant_dept VARCHAR(128) DEFAULT NULL COMMENT ''申请部门'' AFTER customer');
CALL _quote_add_column_if_not_exists('oa_form', 'applicant_office',
  'applicant_office VARCHAR(128) DEFAULT NULL COMMENT ''申请处室/营业所'' AFTER applicant_dept');
CALL _quote_add_column_if_not_exists('oa_form', 'applicant_name',
  'applicant_name VARCHAR(128) DEFAULT NULL COMMENT ''申请人'' AFTER applicant_office');
CALL _quote_add_column_if_not_exists('oa_form', 'urgency',
  'urgency VARCHAR(64) DEFAULT NULL COMMENT ''紧急程度'' AFTER applicant_name');
CALL _quote_add_column_if_not_exists('oa_form', 'product_attr',
  'product_attr VARCHAR(128) DEFAULT NULL COMMENT ''产品属性，表头级默认值'' AFTER urgency');
CALL _quote_add_column_if_not_exists('oa_form', 'price_link_mode',
  'price_link_mode VARCHAR(64) DEFAULT NULL COMMENT ''销售价格联动情况：固定/联动/其他'' AFTER product_attr');
CALL _quote_add_column_if_not_exists('oa_form', 'overseas_sales_mode',
  'overseas_sales_mode VARCHAR(64) DEFAULT NULL COMMENT ''海外销售/海外仓/海外三花标识'' AFTER price_link_mode');
CALL _quote_add_column_if_not_exists('oa_form', 'silver_price',
  'silver_price DECIMAL(18,2) DEFAULT NULL COMMENT ''核算时白银基价，含税，元/吨'' AFTER steel_price');
CALL _quote_add_column_if_not_exists('oa_form', 'gold_price',
  'gold_price DECIMAL(18,2) DEFAULT NULL COMMENT ''核算时黄金基价，含税，元/吨'' AFTER silver_price');
CALL _quote_add_column_if_not_exists('oa_form', 'sus304_price',
  'sus304_price DECIMAL(18,2) DEFAULT NULL COMMENT ''核算时 SUS304 基价，含税，元/吨'' AFTER gold_price');
CALL _quote_add_column_if_not_exists('oa_form', 'sus316l_price',
  'sus316l_price DECIMAL(18,2) DEFAULT NULL COMMENT ''核算时 SUS316L 基价，含税，元/吨'' AFTER sus304_price');
CALL _quote_add_column_if_not_exists('oa_form', 'calc_at',
  'calc_at DATETIME DEFAULT NULL COMMENT ''核算完成时间'' AFTER calc_status');
CALL _quote_add_column_if_not_exists('oa_form', 'classification_status',
  'classification_status VARCHAR(32) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''分类状态：CONFIRMED/PENDING/REJECTED'' AFTER calc_at');
CALL _quote_add_column_if_not_exists('oa_form', 'ingest_log_id',
  'ingest_log_id BIGINT DEFAULT NULL COMMENT ''最近一次接入流水 ID'' AFTER classification_status');

CALL _quote_add_index_if_not_exists('oa_form', 'idx_quote_oa_form_process',
  'ADD INDEX idx_quote_oa_form_process (process_code, quote_scenario)');
CALL _quote_add_index_if_not_exists('oa_form', 'idx_quote_oa_form_source',
  'ADD INDEX idx_quote_oa_form_source (source_type, external_form_no)');
CALL _quote_add_index_if_not_exists('oa_form', 'idx_quote_oa_form_ingest_log',
  'ADD INDEX idx_quote_oa_form_ingest_log (ingest_log_id)');
CALL _quote_add_index_if_not_exists('oa_form', 'idx_quote_oa_form_classification',
  'ADD INDEX idx_quote_oa_form_classification (classification_status)');

-- -----------------------------------------------------------------------------
-- oa_form_item 扩展
-- -----------------------------------------------------------------------------

CALL _quote_add_column_if_not_exists('oa_form_item', 'external_line_id',
  'external_line_id VARCHAR(128) DEFAULT NULL COMMENT ''外部表体行 ID'' AFTER oa_form_id');
CALL _quote_add_column_if_not_exists('oa_form_item', 'customer_code',
  'customer_code VARCHAR(128) DEFAULT NULL COMMENT ''客户编码'' AFTER customer_drawing');
CALL _quote_add_column_if_not_exists('oa_form_item', 'product_attr',
  'product_attr VARCHAR(128) DEFAULT NULL COMMENT ''产品属性，行级覆盖表头'' AFTER spec');
CALL _quote_add_column_if_not_exists('oa_form_item', 'business_type',
  'business_type VARCHAR(64) DEFAULT NULL COMMENT ''业务类型：新品/批量品/衍生品等原始文本'' AFTER product_attr');
CALL _quote_add_column_if_not_exists('oa_form_item', 'first_quote_flag',
  'first_quote_flag TINYINT DEFAULT NULL COMMENT ''是否首次报价：1是，0否'' AFTER business_type');
CALL _quote_add_column_if_not_exists('oa_form_item', 'certification_required',
  'certification_required TINYINT DEFAULT NULL COMMENT ''是否有认证需求：1是，0否'' AFTER first_quote_flag');
CALL _quote_add_column_if_not_exists('oa_form_item', 'origin_country',
  'origin_country VARCHAR(128) DEFAULT NULL COMMENT ''起运国'' AFTER certification_required');
CALL _quote_add_column_if_not_exists('oa_form_item', 'technician_name',
  'technician_name VARCHAR(128) DEFAULT NULL COMMENT ''技术员'' AFTER origin_country');
CALL _quote_add_column_if_not_exists('oa_form_item', 'package_type',
  'package_type VARCHAR(128) DEFAULT NULL COMMENT ''包装类型'' AFTER technician_name');
CALL _quote_add_column_if_not_exists('oa_form_item', 'package_method',
  'package_method VARCHAR(128) DEFAULT NULL COMMENT ''包装方式'' AFTER package_type');
CALL _quote_add_column_if_not_exists('oa_form_item', 'package_component_code',
  'package_component_code VARCHAR(64) DEFAULT NULL COMMENT ''包装组件料号'' AFTER package_method');
CALL _quote_add_column_if_not_exists('oa_form_item', 'package_qty',
  'package_qty DECIMAL(18,6) DEFAULT NULL COMMENT ''包装数量'' AFTER package_component_code');
CALL _quote_add_column_if_not_exists('oa_form_item', 'annual_volume',
  'annual_volume DECIMAL(18,6) DEFAULT NULL COMMENT ''预计年用量'' AFTER support_qty');
CALL _quote_add_column_if_not_exists('oa_form_item', 'project_no',
  'project_no VARCHAR(128) DEFAULT NULL COMMENT ''研发项目号'' AFTER annual_volume');
CALL _quote_add_column_if_not_exists('oa_form_item', 'product_status',
  'product_status VARCHAR(64) DEFAULT NULL COMMENT ''产品状态'' AFTER project_no');
CALL _quote_add_column_if_not_exists('oa_form_item', 'scrap_rate',
  'scrap_rate DECIMAL(18,6) DEFAULT NULL COMMENT ''报废率/净损失率'' AFTER product_status');
CALL _quote_add_column_if_not_exists('oa_form_item', 'unit_labor_cost',
  'unit_labor_cost DECIMAL(18,6) DEFAULT NULL COMMENT ''单件工资，元/只'' AFTER scrap_rate');
CALL _quote_add_column_if_not_exists('oa_form_item', 'classification_status',
  'classification_status VARCHAR(32) NOT NULL DEFAULT ''CONFIRMED'' COMMENT ''行级分类状态：CONFIRMED/PENDING/REJECTED'' AFTER unit_labor_cost');

CALL _quote_add_index_if_not_exists('oa_form_item', 'idx_quote_oa_item_material_no',
  'ADD INDEX idx_quote_oa_item_material_no (material_no)');
CALL _quote_add_index_if_not_exists('oa_form_item', 'idx_quote_oa_item_package',
  'ADD INDEX idx_quote_oa_item_package (package_component_code)');
CALL _quote_add_index_if_not_exists('oa_form_item', 'idx_quote_oa_item_external_line',
  'ADD INDEX idx_quote_oa_item_external_line (external_line_id)');
CALL _quote_add_index_if_not_exists('oa_form_item', 'idx_quote_oa_item_classification',
  'ADD INDEX idx_quote_oa_item_classification (classification_status)');

-- -----------------------------------------------------------------------------
-- 新表
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lp_quote_ingest_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_id VARCHAR(64) NOT NULL COMMENT '接入请求 ID，由接入方传入或系统生成',
  idempotency_key VARCHAR(128) NOT NULL COMMENT '幂等键，建议 source_type + external_form_no + version',
  payload_hash CHAR(64) NOT NULL COMMENT '原始报文 SHA-256',
  source_type VARCHAR(32) NOT NULL COMMENT 'OA/MOCK_OA/MANUAL/EXCEL/TECH/LEGACY',
  source_system VARCHAR(64) DEFAULT NULL COMMENT '来源系统',
  external_form_no VARCHAR(128) DEFAULT NULL COMMENT '外部系统原始单号',
  oa_no VARCHAR(64) DEFAULT NULL COMMENT '标准化后的报价系统单号',
  process_code VARCHAR(64) DEFAULT NULL COMMENT '流程编号',
  process_name VARCHAR(255) DEFAULT NULL COMMENT '流程名称',
  quote_scenario VARCHAR(64) DEFAULT NULL COMMENT '识别出的报价场景',
  ingest_status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/VALIDATING/REJECTED/CLASSIFY_PENDING/IMPORTED/FAILED',
  classification_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'CONFIRMED/PENDING/REJECTED',
  payload_json JSON NOT NULL COMMENT '原始报文',
  normalized_json JSON DEFAULT NULL COMMENT '归一化后的标准报文',
  validation_errors JSON DEFAULT NULL COMMENT '校验错误明细',
  warning_messages JSON DEFAULT NULL COMMENT '非阻断警告',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败摘要',
  received_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  processed_at DATETIME DEFAULT NULL,
  created_by VARCHAR(64) DEFAULT NULL COMMENT '创建人/模拟推送人',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_ingest_request (request_id),
  UNIQUE KEY uk_quote_ingest_idempotency (idempotency_key),
  KEY idx_quote_ingest_form (source_type, external_form_no),
  KEY idx_quote_ingest_oa_no (oa_no),
  KEY idx_quote_ingest_status (ingest_status),
  KEY idx_quote_ingest_process (process_code, quote_scenario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单接入流水表';

CREATE TABLE IF NOT EXISTS lp_quote_ingest_type_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_code VARCHAR(64) NOT NULL COMMENT '规则编码',
  rule_name VARCHAR(128) NOT NULL COMMENT '规则名称',
  priority INT NOT NULL DEFAULT 100 COMMENT '优先级，小者优先',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用',
  source_type VARCHAR(32) DEFAULT NULL COMMENT '来源类型，空表示不限',
  process_code VARCHAR(64) DEFAULT NULL COMMENT '流程编号，空表示不限',
  process_name_keyword VARCHAR(128) DEFAULT NULL COMMENT '流程名称关键词',
  applicant_unit_keyword VARCHAR(128) DEFAULT NULL COMMENT '申请单位/部门关键词',
  business_type_keyword VARCHAR(128) DEFAULT NULL COMMENT '业务类型关键词',
  product_attr_keyword VARCHAR(128) DEFAULT NULL COMMENT '产品属性关键词',
  first_quote_flag TINYINT DEFAULT NULL COMMENT '是否首次报价条件，空表示不限',
  target_business_unit_type VARCHAR(32) NOT NULL COMMENT '识别结果：COMMERCIAL/HOUSEHOLD/UNKNOWN',
  target_quote_scenario VARCHAR(64) NOT NULL COMMENT '识别结果报价场景',
  confidence INT NOT NULL DEFAULT 100 COMMENT '置信度，100 表示可自动确认',
  remark VARCHAR(500) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_ingest_type_rule_code (rule_code),
  KEY idx_quote_ingest_type_rule_match (enabled, priority, process_code),
  KEY idx_quote_ingest_type_rule_target (target_business_unit_type, target_quote_scenario)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单接入分类规则表';

CREATE TABLE IF NOT EXISTS lp_oa_form_extra_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'oa_form_item.id，空表示表头字段',
  field_code VARCHAR(128) NOT NULL COMMENT '标准字段编码',
  field_name VARCHAR(255) NOT NULL COMMENT '字段中文名',
  field_value VARCHAR(1000) DEFAULT NULL COMMENT '字段值文本',
  field_value_number DECIMAL(18,6) DEFAULT NULL COMMENT '字段值数字',
  field_value_date DATE DEFAULT NULL COMMENT '字段值日期',
  value_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/DATE/BOOLEAN/JSON',
  source_field_name VARCHAR(255) DEFAULT NULL COMMENT '原始单据字段名',
  source_field_path VARCHAR(500) DEFAULT NULL COMMENT '原始报文路径',
  ingest_log_id BIGINT DEFAULT NULL COMMENT '接入流水 ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oa_extra_field (oa_form_id, oa_form_item_id, field_code),
  KEY idx_oa_extra_form (oa_form_id),
  KEY idx_oa_extra_item (oa_form_item_id),
  KEY idx_oa_extra_code (field_code),
  KEY idx_oa_extra_ingest (ingest_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA报价单扩展字段表';

CREATE TABLE IF NOT EXISTS lp_oa_form_extra_fee (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'oa_form_item.id，空表示表头级费用',
  fee_code VARCHAR(64) NOT NULL COMMENT '费用编码',
  fee_name VARCHAR(128) NOT NULL COMMENT '费用名称',
  fee_category VARCHAR(64) NOT NULL COMMENT '费用分类：TOOLING/MOLD/CERTIFICATION/EQUIPMENT/CUTTER/LABOR/SCRAP/OTHER',
  amount DECIMAL(18,6) DEFAULT NULL COMMENT '费用金额',
  unit VARCHAR(32) DEFAULT NULL COMMENT '单位',
  tax_included TINYINT DEFAULT NULL COMMENT '是否含税：1是，0否，空表示未知',
  allocation_method VARCHAR(64) DEFAULT NULL COMMENT '分摊方式：NONE/ANNUAL_VOLUME/MANUAL/PROJECT_EXEMPT',
  allocated_amount DECIMAL(18,6) DEFAULT NULL COMMENT '分摊后金额',
  bearer VARCHAR(64) DEFAULT NULL COMMENT '承担方：生产方/销售方/其他',
  project_no VARCHAR(128) DEFAULT NULL COMMENT '研发项目号',
  source_type VARCHAR(32) NOT NULL DEFAULT 'INGEST' COMMENT '来源：INGEST/MANUAL/CALC',
  source_field_name VARCHAR(255) DEFAULT NULL COMMENT '原始字段名',
  remark VARCHAR(500) DEFAULT NULL,
  ingest_log_id BIGINT DEFAULT NULL COMMENT '接入流水 ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oa_extra_fee (oa_form_id, oa_form_item_id, fee_code),
  KEY idx_oa_extra_fee_form (oa_form_id),
  KEY idx_oa_extra_fee_item (oa_form_item_id),
  KEY idx_oa_extra_fee_category (fee_category),
  KEY idx_oa_extra_fee_ingest (ingest_log_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA报价单额外费用表';

CREATE TABLE IF NOT EXISTS lp_quote_bom_status (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  oa_no VARCHAR(64) NOT NULL COMMENT 'OA/报价单号',
  product_code VARCHAR(64) DEFAULT NULL COMMENT '产品料号',
  product_model VARCHAR(128) DEFAULT NULL COMMENT '三花型号',
  customer_code VARCHAR(128) DEFAULT NULL COMMENT '客户编码',
  package_type VARCHAR(128) DEFAULT NULL COMMENT '包装类型',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  bom_status VARCHAR(32) NOT NULL DEFAULT 'NOT_CHECKED' COMMENT 'NOT_CHECKED/SYNCED/REUSED_CURRENT_MONTH/CURRENT_MONTH_QUOTED/U9_BOM_EXISTS/NO_BOM/ENTRY_PENDING/ENTRY_IN_PROGRESS/MANUAL_ENTERED/EXPIRED/CHECK_FAILED',
  bom_source VARCHAR(32) DEFAULT NULL COMMENT 'U9/MANUAL/TECH/CMS/UNKNOWN',
  bom_purpose VARCHAR(64) DEFAULT NULL COMMENT 'BOM 生产目的',
  bom_version VARCHAR(128) DEFAULT NULL COMMENT 'BOM 版本',
  effective_from DATE DEFAULT NULL COMMENT 'BOM 生效日期',
  effective_to DATE DEFAULT NULL COMMENT 'BOM 失效日期',
  checked_at DATETIME DEFAULT NULL COMMENT '最近检查时间',
  sync_batch_id VARCHAR(64) DEFAULT NULL COMMENT '同步/构建批次',
  manual_task_no VARCHAR(128) DEFAULT NULL COMMENT '技术补录任务号',
  technician_name VARCHAR(128) DEFAULT NULL COMMENT '负责技术员',
  lock_owner VARCHAR(128) DEFAULT NULL COMMENT '当前锁定人/任务',
  lock_until DATETIME DEFAULT NULL COMMENT '锁定截止时间',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '检查失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_bom_status_item (oa_form_item_id),
  KEY idx_quote_bom_status_oa (oa_no),
  KEY idx_quote_bom_status_product (product_code),
  KEY idx_quote_bom_status_status (bom_status),
  KEY idx_quote_bom_status_lock (product_code, lock_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价单产品 BOM 状态表';

CREATE TABLE IF NOT EXISTS lp_quote_writeback_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT DEFAULT NULL COMMENT 'oa_form_item.id，空表示表头级回写',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  external_form_no VARCHAR(128) DEFAULT NULL COMMENT '外部单号',
  target_system VARCHAR(64) NOT NULL DEFAULT 'OA' COMMENT '目标系统',
  writeback_type VARCHAR(64) NOT NULL COMMENT 'TOTAL_NO_SHIP_COST/COST_DETAIL/STATUS 等',
  writeback_status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/FAILED/SKIPPED',
  request_payload JSON NOT NULL COMMENT '待回写报文',
  response_payload JSON DEFAULT NULL COMMENT '外部系统响应',
  retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
  next_retry_at DATETIME DEFAULT NULL COMMENT '下次重试时间',
  error_message VARCHAR(1000) DEFAULT NULL COMMENT '失败原因',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_quote_writeback_oa (oa_no),
  KEY idx_quote_writeback_status (writeback_status, next_retry_at),
  KEY idx_quote_writeback_form (oa_form_id, oa_form_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价结果回写任务表';

-- -----------------------------------------------------------------------------
-- 初始化分类规则
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

-- -----------------------------------------------------------------------------
-- 历史数据回填
-- -----------------------------------------------------------------------------

UPDATE oa_form
SET source_type = 'LEGACY'
WHERE source_type IS NULL;

UPDATE oa_form
SET quote_scenario = 'UNKNOWN'
WHERE quote_scenario IS NULL;

UPDATE oa_form
SET classification_status = 'CONFIRMED'
WHERE classification_status IS NULL OR classification_status = '';

UPDATE oa_form_item
SET classification_status = 'CONFIRMED'
WHERE classification_status IS NULL OR classification_status = '';

DROP PROCEDURE IF EXISTS _quote_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _quote_add_index_if_not_exists;
