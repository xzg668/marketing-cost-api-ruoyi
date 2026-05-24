-- =============================================================================
-- V121: 报价单 OA 原始表单 Excel 接入模型补齐
-- -----------------------------------------------------------------------------
-- 业务口径：
--   1. 第一阶段导入六种“仿泛微 OA 原始表单样式 Excel”。
--   2. oa_form 补齐后续成本核算匹配需要的表头维度字段。
--   3. 表头扩展字段和产品行扩展字段拆成两张表，避免 HEADER/ITEM 混合粒度。
--   4. 旧 lp_oa_form_extra_field 暂保留兼容，不再作为新导入口径的目标表。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v121_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v121_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v121_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v121_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

-- -----------------------------------------------------------------------------
-- oa_form：补齐成本核算维度上下文字段
-- -----------------------------------------------------------------------------

CALL v121_add_column_if_not_exists(
  'oa_form',
  'accounting_period_month',
  '`accounting_period_month` VARCHAR(7) DEFAULT NULL COMMENT ''核算匹配月份，由申请日期派生，格式 YYYY-MM'' AFTER `business_unit_type`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'applicant_unit',
  '`applicant_unit` VARCHAR(128) DEFAULT NULL COMMENT ''申请单位，例如商用制冷业务单元/家代商业务单元'' AFTER `applicant_name`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'source_company',
  '`source_company` VARCHAR(128) DEFAULT NULL COMMENT ''报价单所属公司原始值'' AFTER `customer`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'source_business_division',
  '`source_business_division` VARCHAR(128) DEFAULT NULL COMMENT ''报价单事业部原始值'' AFTER `source_company`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'expense_product_category',
  '`expense_product_category` VARCHAR(64) DEFAULT NULL COMMENT ''费用匹配产品口径：商用直销产品/家代商代销产品'' AFTER `quote_scenario`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'trade_terms',
  '`trade_terms` VARCHAR(128) DEFAULT NULL COMMENT ''贸易条款，如 DDP/FOB 等'' AFTER `overseas_sales_mode`'
);

CALL v121_add_column_if_not_exists(
  'oa_form',
  'exchange_rate',
  '`exchange_rate` DECIMAL(18,6) DEFAULT NULL COMMENT ''报价单汇率'' AFTER `trade_terms`'
);

CALL v121_add_index_if_not_exists(
  'oa_form',
  'idx_quote_oa_form_accounting_period',
  'KEY `idx_quote_oa_form_accounting_period` (`business_unit_type`, `accounting_period_month`)'
);

CALL v121_add_index_if_not_exists(
  'oa_form',
  'idx_quote_oa_form_expense_dimension',
  'KEY `idx_quote_oa_form_expense_dimension` (`business_unit_type`, `expense_product_category`, `source_company`, `source_business_division`, `applicant_dept`, `applicant_office`)'
);

-- -----------------------------------------------------------------------------
-- lp_oa_form_header_extra_field：表头扩展字段
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lp_oa_form_header_extra_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT '所属报价单表头 ID',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元租户口径：COMMERCIAL/HOUSEHOLD',
  field_code VARCHAR(128) NOT NULL COMMENT '系统字段编码',
  field_name VARCHAR(255) NOT NULL COMMENT '原始字段名/显示名',
  field_value VARCHAR(2000) DEFAULT NULL COMMENT '原始文本值',
  field_value_number DECIMAL(18,6) DEFAULT NULL COMMENT '数值型值',
  field_value_date DATE DEFAULT NULL COMMENT '日期型值',
  value_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/DATE/BOOLEAN/JSON',
  source_field_name VARCHAR(255) DEFAULT NULL COMMENT '来源字段名',
  source_field_path VARCHAR(500) DEFAULT NULL COMMENT '来源路径，如 sheet/cell/json path',
  ingest_log_id BIGINT DEFAULT NULL COMMENT '接入流水 ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oa_header_extra_field (`business_unit_type`, `oa_form_id`, `field_code`),
  KEY idx_oa_header_extra_form (`oa_form_id`),
  KEY idx_oa_header_extra_code (`field_code`),
  KEY idx_oa_header_extra_ingest (`ingest_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA报价单表头扩展字段表';

-- -----------------------------------------------------------------------------
-- lp_oa_form_item_extra_field：产品行扩展字段
-- -----------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS lp_oa_form_item_extra_field (
  id BIGINT NOT NULL AUTO_INCREMENT,
  oa_form_id BIGINT NOT NULL COMMENT '冗余所属报价单表头 ID，方便按单查询',
  oa_form_item_id BIGINT NOT NULL COMMENT '所属报价单产品行 ID',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元租户口径：COMMERCIAL/HOUSEHOLD',
  field_code VARCHAR(128) NOT NULL COMMENT '系统字段编码',
  field_name VARCHAR(255) NOT NULL COMMENT '原始字段名/显示名',
  field_value VARCHAR(2000) DEFAULT NULL COMMENT '原始文本值',
  field_value_number DECIMAL(18,6) DEFAULT NULL COMMENT '数值型值',
  field_value_date DATE DEFAULT NULL COMMENT '日期型值',
  value_type VARCHAR(32) NOT NULL DEFAULT 'TEXT' COMMENT 'TEXT/NUMBER/DATE/BOOLEAN/JSON',
  source_field_name VARCHAR(255) DEFAULT NULL COMMENT '来源字段名',
  source_field_path VARCHAR(500) DEFAULT NULL COMMENT '来源路径，如 sheet/cell/json path',
  ingest_log_id BIGINT DEFAULT NULL COMMENT '接入流水 ID',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_oa_item_extra_field (`business_unit_type`, `oa_form_item_id`, `field_code`),
  KEY idx_oa_item_extra_form (`oa_form_id`),
  KEY idx_oa_item_extra_item (`oa_form_item_id`),
  KEY idx_oa_item_extra_code (`field_code`),
  KEY idx_oa_item_extra_ingest (`ingest_log_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='OA报价单产品行扩展字段表';

-- -----------------------------------------------------------------------------
-- lp_oa_form_extra_fee：补齐费用项粒度与来源路径
-- -----------------------------------------------------------------------------

CALL v121_add_column_if_not_exists(
  'lp_oa_form_extra_fee',
  'fee_scope',
  '`fee_scope` VARCHAR(16) DEFAULT NULL COMMENT ''费用归属：HEADER/ITEM'' AFTER `oa_form_item_id`'
);

CALL v121_add_column_if_not_exists(
  'lp_oa_form_extra_fee',
  'business_unit_type',
  '`business_unit_type` VARCHAR(32) DEFAULT NULL COMMENT ''业务单元租户口径：COMMERCIAL/HOUSEHOLD'' AFTER `fee_scope`'
);

CALL v121_add_column_if_not_exists(
  'lp_oa_form_extra_fee',
  'source_field_path',
  '`source_field_path` VARCHAR(500) DEFAULT NULL COMMENT ''来源路径，如 sheet/cell/json path'' AFTER `source_field_name`'
);

CALL v121_add_index_if_not_exists(
  'lp_oa_form_extra_fee',
  'idx_oa_extra_fee_scope',
  'KEY `idx_oa_extra_fee_scope` (`fee_scope`, `oa_form_item_id`)'
);

CALL v121_add_index_if_not_exists(
  'lp_oa_form_extra_fee',
  'idx_oa_extra_fee_but',
  'KEY `idx_oa_extra_fee_but` (`business_unit_type`)'
);

DROP PROCEDURE IF EXISTS v121_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v121_add_index_if_not_exists;
