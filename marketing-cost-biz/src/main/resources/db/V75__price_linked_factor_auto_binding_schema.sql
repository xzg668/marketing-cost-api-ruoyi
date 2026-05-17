-- =====================================================================
-- V75: 联动价 Excel 公式自动绑定影响因素 - 数据模型
--
-- 目标：
--   1) 影响因素身份统一去重：lp_factor_identity
--   2) 影响因素月度价格汇总：lp_factor_monthly_price
--   3) Excel 上传批次审计：lp_factor_upload_batch
--   4) 某次上传 sheet+行号 到影响因素身份的映射：lp_factor_row_ref
--   5) 料号历史关系仅用于校验：lp_material_factor_binding_std
--   6) 价格变更日志、Excel 自动绑定导入日志
--   7) 扩展现有 lp_price_variable_binding，保留老计算链路兼容
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_factor_identity (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  factor_seq_no VARCHAR(64) NOT NULL COMMENT '影响因素表序号',
  factor_name VARCHAR(255) NOT NULL COMMENT '价表影响因素名称',
  short_name VARCHAR(128) NOT NULL COMMENT '简称',
  price_source VARCHAR(64) NOT NULL COMMENT '取价来源',
  identity_hash CHAR(64) DEFAULT NULL COMMENT '归一化身份 hash',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_identity (business_unit_type, factor_seq_no, factor_name, short_name, price_source),
  KEY idx_factor_identity_short_name (business_unit_type, short_name),
  KEY idx_factor_identity_hash (identity_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素稳定身份：价格不参与身份判断';

CREATE TABLE IF NOT EXISTS lp_factor_monthly_price (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_identity_id BIGINT NOT NULL COMMENT 'lp_factor_identity.id',
  price_month CHAR(7) NOT NULL COMMENT '价格月份 yyyy-MM',
  price DECIMAL(20,6) DEFAULT NULL COMMENT '当月当前价格',
  tax_included TINYINT NOT NULL DEFAULT 1 COMMENT '是否含税',
  source_upload_batch_id BIGINT DEFAULT NULL COMMENT '最近来源上传批次',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/INACTIVE',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_monthly_price (factor_identity_id, price_month),
  KEY idx_factor_monthly_price_month (price_month),
  KEY idx_factor_monthly_price_batch (source_upload_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素月度价格汇总：月度调价主数据';

CREATE TABLE IF NOT EXISTS lp_factor_upload_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  batch_no VARCHAR(64) NOT NULL COMMENT '上传批次号',
  import_type VARCHAR(32) NOT NULL DEFAULT 'MONTHLY_LINKED_FACTOR' COMMENT '导入类型',
  price_month CHAR(7) NOT NULL COMMENT '价格月份 yyyy-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  file_name VARCHAR(255) DEFAULT NULL COMMENT '上传文件名',
  file_sha256 CHAR(64) DEFAULT NULL COMMENT '文件 hash',
  content_hash CHAR(64) DEFAULT NULL COMMENT '归一化内容 hash',
  uploaded_by VARCHAR(64) DEFAULT NULL COMMENT '上传人',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/PARTIAL/FAILED',
  factor_sheet_count INT NOT NULL DEFAULT 0,
  linked_sheet_count INT NOT NULL DEFAULT 0,
  factor_row_count INT NOT NULL DEFAULT 0,
  linked_row_count INT NOT NULL DEFAULT 0,
  auto_binding_count INT NOT NULL DEFAULT 0,
  warning_count INT NOT NULL DEFAULT 0,
  error_count INT NOT NULL DEFAULT 0,
  error_message TEXT DEFAULT NULL,
  started_at DATETIME DEFAULT NULL,
  finished_at DATETIME DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_upload_batch_no (batch_no),
  KEY idx_factor_upload_context (business_unit_type, price_month, uploaded_by),
  KEY idx_factor_upload_content_hash (content_hash),
  KEY idx_factor_upload_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素/联动价 Excel 上传批次审计';

CREATE TABLE IF NOT EXISTS lp_factor_row_ref (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_upload_batch_id BIGINT NOT NULL COMMENT 'lp_factor_upload_batch.id',
  source_workbook_name VARCHAR(255) DEFAULT NULL COMMENT '公式外部工作簿名，可为空',
  source_sheet_name VARCHAR(128) NOT NULL COMMENT 'Excel sheet 名',
  source_row_number INT NOT NULL COMMENT 'Excel 1-based 行号',
  factor_identity_id BIGINT NOT NULL COMMENT 'lp_factor_identity.id',
  factor_monthly_price_id BIGINT NOT NULL COMMENT 'lp_factor_monthly_price.id',
  factor_seq_no VARCHAR(64) DEFAULT NULL COMMENT '导入时序号快照',
  short_name VARCHAR(128) DEFAULT NULL COMMENT '导入时简称快照',
  price_source VARCHAR(64) DEFAULT NULL COMMENT '导入时取价来源快照',
  price DECIMAL(20,6) DEFAULT NULL COMMENT '导入时价格快照',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_row_ref (factor_upload_batch_id, source_sheet_name, source_row_number),
  KEY idx_factor_row_ref_identity (factor_identity_id),
  KEY idx_factor_row_ref_monthly_price (factor_monthly_price_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='上传批次内 sheet+行号 到影响因素身份的映射';

CREATE TABLE IF NOT EXISTS lp_material_factor_binding_std (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  material_code VARCHAR(64) NOT NULL COMMENT '物料代码',
  supplier_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '供应商代码，空串表示未提供',
  token_name VARCHAR(32) NOT NULL COMMENT '材料含税价格/废料含税价格等',
  factor_identity_id BIGINT NOT NULL COMMENT 'lp_factor_identity.id',
  source VARCHAR(32) NOT NULL DEFAULT 'EXCEL_FORMULA' COMMENT '关系来源',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/CONFLICT/INACTIVE',
  first_import_batch_id BIGINT DEFAULT NULL,
  last_import_batch_id BIGINT DEFAULT NULL,
  last_formula TEXT DEFAULT NULL COMMENT '最近一次识别到的 Excel 公式',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_factor_binding_std (business_unit_type, material_code, supplier_code, token_name, status),
  KEY idx_material_factor_binding_identity (factor_identity_id),
  KEY idx_material_factor_binding_material (business_unit_type, material_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='料号历史影响因素关系：仅用于校验和冲突提示';

CREATE TABLE IF NOT EXISTS lp_factor_monthly_price_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_monthly_price_id BIGINT DEFAULT NULL,
  factor_identity_id BIGINT NOT NULL,
  price_month CHAR(7) NOT NULL,
  old_price DECIMAL(20,6) DEFAULT NULL,
  new_price DECIMAL(20,6) DEFAULT NULL,
  change_type VARCHAR(32) NOT NULL COMMENT 'CREATE/UPDATE/NO_CHANGE',
  source_upload_batch_id BIGINT DEFAULT NULL,
  changed_by VARCHAR(64) DEFAULT NULL,
  remark VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_factor_price_log_identity_month (factor_identity_id, price_month),
  KEY idx_factor_price_log_batch (source_upload_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素月度价格变更日志';

CREATE TABLE IF NOT EXISTS lp_excel_auto_binding_import_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_upload_batch_id BIGINT DEFAULT NULL,
  linked_item_id BIGINT DEFAULT NULL,
  material_code VARCHAR(64) DEFAULT NULL,
  supplier_code VARCHAR(64) DEFAULT NULL,
  token_name VARCHAR(32) DEFAULT NULL,
  action VARCHAR(32) NOT NULL COMMENT 'CREATE_HISTORY/CONSISTENT/CONFLICT/FAILED/AUTO_BOUND/SKIPPED',
  status VARCHAR(16) NOT NULL COMMENT 'SUCCESS/WARNING/FAILED',
  factor_identity_id BIGINT DEFAULT NULL,
  factor_monthly_price_id BIGINT DEFAULT NULL,
  source_workbook_name VARCHAR(255) DEFAULT NULL,
  source_sheet_name VARCHAR(128) DEFAULT NULL,
  source_cell_ref VARCHAR(32) DEFAULT NULL,
  excel_formula TEXT DEFAULT NULL,
  message VARCHAR(1024) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_auto_binding_log_batch (factor_upload_batch_id),
  KEY idx_auto_binding_log_item (linked_item_id),
  KEY idx_auto_binding_log_material (material_code),
  KEY idx_auto_binding_log_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Excel 公式自动绑定导入结果明细日志';

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v75;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v75(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v75;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v75(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'factor_identity_id',
  'BIGINT NULL COMMENT ''V75 自动绑定到影响因素身份'' AFTER price_source');
CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'factor_monthly_price_id',
  'BIGINT NULL COMMENT ''导入当时解析到的月度价格，仅审计快照'' AFTER factor_identity_id');
CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'factor_upload_batch_id',
  'BIGINT NULL COMMENT ''来源影响因素上传批次'' AFTER factor_monthly_price_id');
CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'excel_source_sheet_name',
  'VARCHAR(128) NULL COMMENT ''Excel 公式引用 sheet'' AFTER factor_upload_batch_id');
CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'excel_source_cell_ref',
  'VARCHAR(32) NULL COMMENT ''Excel 公式引用单元格，如 E64'' AFTER excel_source_sheet_name');
CALL add_column_if_not_exists_v75('lp_price_variable_binding', 'excel_formula',
  'TEXT NULL COMMENT ''识别来源单价列公式'' AFTER excel_source_cell_ref');

CALL add_index_if_not_exists_v75('lp_price_variable_binding', 'idx_binding_factor_identity',
  'KEY idx_binding_factor_identity (factor_identity_id)');
CALL add_index_if_not_exists_v75('lp_price_variable_binding', 'idx_binding_factor_batch',
  'KEY idx_binding_factor_batch (factor_upload_batch_id)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v75;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v75;
