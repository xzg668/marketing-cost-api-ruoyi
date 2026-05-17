-- =====================================================================
-- V80: 月度调价批次价与联动价导入生效策略
--
-- 目标：
--   1) 用 lp_factor_adjust_batch / lp_factor_adjust_price 保存“月度调价批次价”。
--   2) lp_factor_monthly_price 仍只表示“日常报价生效价”。
--   3) REPRICE_ONLY 调价批次不写 lp_factor_monthly_price，避免污染日常报价。
--   4) 联动价与影响因素 Excel 导入批次记录“仅新增 / 覆盖生效”策略。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_factor_adjust_batch (
  id BIGINT NOT NULL AUTO_INCREMENT,
  adjust_batch_no VARCHAR(64) NOT NULL COMMENT '调价批次号',
  pricing_month CHAR(7) NOT NULL COMMENT '目标价格月份 yyyy-MM',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元',
  usage_scope VARCHAR(32) NOT NULL COMMENT 'REPRICE_ONLY/REPRICE_AND_DAILY',
  source_type VARCHAR(32) NOT NULL DEFAULT 'ADJUST_EXCEL_IMPORT' COMMENT 'ADJUST_EXCEL_IMPORT/MANUAL_ADJUST',
  source_file_name VARCHAR(255) DEFAULT NULL COMMENT '上传文件名',
  file_sha256 CHAR(64) DEFAULT NULL COMMENT '原始文件 hash',
  content_hash CHAR(64) DEFAULT NULL COMMENT '归一化内容 hash',
  total_count INT NOT NULL DEFAULT 0 COMMENT '识别总数',
  changed_count INT NOT NULL DEFAULT 0 COMMENT '价格变化数',
  no_change_count INT NOT NULL DEFAULT 0 COMMENT '价格未变化数',
  skipped_count INT NOT NULL DEFAULT 0 COMMENT '跳过数',
  failed_count INT NOT NULL DEFAULT 0 COMMENT '失败数',
  status VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SUCCESS/PARTIAL_SUCCESS/FAILED',
  uploaded_by VARCHAR(64) DEFAULT NULL COMMENT '上传人',
  uploaded_at DATETIME DEFAULT NULL COMMENT '上传时间',
  remark VARCHAR(512) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_adjust_batch_no (adjust_batch_no),
  KEY idx_factor_adjust_context (business_unit_type, pricing_month, usage_scope, deleted),
  KEY idx_factor_adjust_uploaded (uploaded_by, uploaded_at),
  KEY idx_factor_adjust_status (status, deleted),
  KEY idx_factor_adjust_content_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素月度调价批次：可只用于历史报价重算，也可同步为日常报价价';

CREATE TABLE IF NOT EXISTS lp_factor_adjust_price (
  id BIGINT NOT NULL AUTO_INCREMENT,
  adjust_batch_id BIGINT NOT NULL COMMENT 'lp_factor_adjust_batch.id',
  factor_identity_id BIGINT DEFAULT NULL COMMENT 'lp_factor_identity.id，匹配失败时可为空',
  factor_monthly_price_id BIGINT DEFAULT NULL COMMENT '同步为日常报价价时对应 lp_factor_monthly_price.id',
  factor_seq_no VARCHAR(64) DEFAULT NULL COMMENT '影响因素序号快照',
  factor_name VARCHAR(255) DEFAULT NULL COMMENT '价表影响因素名称快照',
  short_name VARCHAR(128) DEFAULT NULL COMMENT '简称快照',
  price_source VARCHAR(64) DEFAULT NULL COMMENT '取价来源快照',
  unit VARCHAR(64) DEFAULT NULL COMMENT '单位快照',
  original_price DECIMAL(20,6) DEFAULT NULL COMMENT '导入前价格或 Excel 原价',
  adjusted_price DECIMAL(20,6) DEFAULT NULL COMMENT '调价后价格',
  price_delta DECIMAL(20,6) DEFAULT NULL COMMENT '调价差额',
  change_rate DECIMAL(18,6) DEFAULT NULL COMMENT '涨跌幅',
  match_method VARCHAR(32) DEFAULT NULL COMMENT 'SYSTEM_ID/IDENTITY_FIELDS',
  apply_to_daily TINYINT NOT NULL DEFAULT 0 COMMENT '是否同步为日常报价生效价',
  status VARCHAR(16) NOT NULL DEFAULT 'CHANGED' COMMENT 'CHANGED/NO_CHANGE/FAILED/SKIPPED',
  fail_reason VARCHAR(1024) DEFAULT NULL COMMENT '失败或跳过原因',
  source_sheet_name VARCHAR(128) DEFAULT NULL COMMENT '来源 sheet',
  source_row_number INT DEFAULT NULL COMMENT '来源 Excel 1-based 行号',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  KEY idx_factor_adjust_price_batch (adjust_batch_id, deleted),
  KEY idx_factor_adjust_price_identity (factor_identity_id, deleted),
  KEY idx_factor_adjust_price_monthly (factor_monthly_price_id),
  KEY idx_factor_adjust_price_status (status, deleted),
  KEY idx_factor_adjust_price_source_row (adjust_batch_id, source_sheet_name, source_row_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素月度调价批次价格明细';

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v80;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v80(
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

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v80;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v80(
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

CALL add_column_if_not_exists_v80('lp_factor_upload_batch', 'import_purpose',
  'VARCHAR(32) NULL COMMENT ''MONTHLY_LINKED_FACTOR/LINKED_APPEND_ONLY/LINKED_OVERRIDE_EFFECTIVE/MONTHLY_ADJUST'' AFTER import_type');
CALL add_column_if_not_exists_v80('lp_factor_upload_batch', 'effective_strategy',
  'VARCHAR(32) NULL COMMENT ''APPEND_ONLY/OVERRIDE_EFFECTIVE，联动价与影响因素导入生效策略'' AFTER import_purpose');

CALL add_column_if_not_exists_v80('lp_factor_monthly_price', 'latest_adjust_batch_id',
  'BIGINT NULL COMMENT ''最近同步到日常报价价的调价批次'' AFTER source_upload_batch_id');
CALL add_column_if_not_exists_v80('lp_factor_monthly_price', 'latest_adjust_source_type',
  'VARCHAR(32) NULL COMMENT ''最近调价来源：ADJUST_EXCEL_IMPORT/MANUAL_ADJUST'' AFTER latest_adjust_batch_id');
CALL add_column_if_not_exists_v80('lp_factor_monthly_price', 'latest_adjusted_by',
  'VARCHAR(64) NULL COMMENT ''最近调价人'' AFTER latest_adjust_source_type');
CALL add_column_if_not_exists_v80('lp_factor_monthly_price', 'latest_adjusted_at',
  'DATETIME NULL COMMENT ''最近调价时间'' AFTER latest_adjusted_by');
CALL add_column_if_not_exists_v80('lp_factor_monthly_price', 'source_tag',
  'VARCHAR(32) NULL COMMENT ''当前日常报价价来源标签：EXCEL_IMPORT/MANUAL_ADJUST/ADJUST_IMPORT'' AFTER latest_adjusted_at');

CALL add_column_if_not_exists_v80('lp_factor_monthly_price_change_log', 'adjust_batch_id',
  'BIGINT NULL COMMENT ''关联 lp_factor_adjust_batch，仅同步为日常报价价时填写'' AFTER source_upload_batch_id');
CALL add_column_if_not_exists_v80('lp_factor_monthly_price_change_log', 'source_type',
  'VARCHAR(32) NULL COMMENT ''EXCEL_IMPORT/MANUAL_ADJUST/ADJUST_IMPORT'' AFTER adjust_batch_id');

CALL add_index_if_not_exists_v80('lp_factor_upload_batch', 'idx_factor_upload_purpose_strategy',
  'KEY idx_factor_upload_purpose_strategy (import_purpose, effective_strategy)');
CALL add_index_if_not_exists_v80('lp_factor_monthly_price', 'idx_factor_monthly_latest_adjust',
  'KEY idx_factor_monthly_latest_adjust (latest_adjust_batch_id)');
CALL add_index_if_not_exists_v80('lp_factor_monthly_price', 'idx_factor_monthly_source_tag',
  'KEY idx_factor_monthly_source_tag (source_tag)');
CALL add_index_if_not_exists_v80('lp_factor_monthly_price_change_log', 'idx_factor_price_log_adjust_batch',
  'KEY idx_factor_price_log_adjust_batch (adjust_batch_id)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v80;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v80;
