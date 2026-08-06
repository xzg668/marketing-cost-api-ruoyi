-- 月度联动价导入行级失败明细。
-- 批次表只保留汇总数量，本表保存用户可回看的业务失败原因与处理建议。
CREATE TABLE IF NOT EXISTS lp_factor_upload_row_error (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_upload_batch_id BIGINT NOT NULL COMMENT 'lp_factor_upload_batch.id',
  source_workbook_name VARCHAR(255) DEFAULT NULL COMMENT '上传文件名',
  source_sheet_name VARCHAR(128) DEFAULT NULL COMMENT 'Excel sheet 名',
  excel_row_number INT DEFAULT NULL COMMENT 'Excel 1-based 行号',
  material_code VARCHAR(64) DEFAULT NULL COMMENT '物料代码',
  material_name VARCHAR(255) DEFAULT NULL COMMENT '物料名称快照',
  supplier_code VARCHAR(64) DEFAULT NULL COMMENT '供应商代码快照',
  order_type VARCHAR(64) DEFAULT NULL COMMENT '订单类型快照',
  formula TEXT DEFAULT NULL COMMENT '导入公式原文',
  formula_effective_date DATE DEFAULT NULL COMMENT '本次公式生效日期',
  error_stage VARCHAR(32) NOT NULL DEFAULT 'ROW_IMPORT' COMMENT '失败阶段',
  error_code VARCHAR(64) NOT NULL DEFAULT 'IMPORT_ERROR' COMMENT '稳定错误编码',
  error_message VARCHAR(2048) NOT NULL COMMENT '用户可读失败原因',
  suggestion VARCHAR(1024) DEFAULT NULL COMMENT '处理建议',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_factor_upload_row_error_batch (factor_upload_batch_id, excel_row_number),
  KEY idx_factor_upload_row_error_material (material_code),
  KEY idx_factor_upload_row_error_code (error_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素/联动价 Excel 上传批次行级失败明细';
