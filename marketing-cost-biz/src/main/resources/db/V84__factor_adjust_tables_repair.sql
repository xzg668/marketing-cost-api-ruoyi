-- V84：修复历史库缺少 V5 月度调价批次表的问题。
--
-- 背景：
--   部分环境已经存在 V75 相关表，但 V80 未完整落库，导致查询调价历史时报：
--     Table 'marketing_cost.lp_factor_adjust_price' doesn't exist
--
-- 这两个 CREATE TABLE 保持与 V80 定义一致，使用 IF NOT EXISTS，避免影响已建表和历史数据。

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
