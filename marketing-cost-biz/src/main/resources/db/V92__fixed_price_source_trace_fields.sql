-- =============================================================================
-- V92  固定采购价 / 结算固定价来源追溯字段扩展                         2026-05-18
--
-- 强制 connection charset 为 utf8mb4，防止中文备注入库乱码
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   固定采购价5 与 家用结算价9 继续共用 lp_price_fixed_item，但通过 source_type
--   与 source_system 强隔离。固定采购价非 U9 行后续按 Excel id 幂等，因此
--   external_row_id 用于固定采购价非 U9 的重复导入更新依据。
--
-- 说明：
--   家用结算价9 最后一列是“价格或备注”混合列：数字写 fixed_price 和
--   settle_reference_price；“不用提供”等非数字写 settle_reference_text，
--   fixed_price 允许为空且不参与取价。
-- =============================================================================

DROP PROCEDURE IF EXISTS v92_extend_fixed_price_item;
DELIMITER $$
CREATE PROCEDURE v92_extend_fixed_price_item()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
               AND column_name='fixed_price' AND is_nullable='NO') THEN
    ALTER TABLE lp_price_fixed_item
      MODIFY COLUMN fixed_price DECIMAL(18,6) NULL
        COMMENT '固定价实际取价单价；结算固定价备注行允许为空';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
               AND column_name='planned_price') THEN
    ALTER TABLE lp_price_fixed_item
      MODIFY COLUMN planned_price DECIMAL(18,6) NULL COMMENT '计划价（结算固定价专用）';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
               AND column_name='base_settle_price') THEN
    ALTER TABLE lp_price_fixed_item
      MODIFY COLUMN base_settle_price DECIMAL(18,6) NULL COMMENT '结算固定价专用：基准结算价，仅追溯不取价';
  END IF;

  IF EXISTS (SELECT 1 FROM information_schema.columns
             WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
               AND column_name='linked_settle_price') THEN
    ALTER TABLE lp_price_fixed_item
      MODIFY COLUMN linked_settle_price DECIMAL(18,6) NULL COMMENT '结算固定价专用：联动结算价，仅追溯不取价';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='source_system') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN source_system VARCHAR(32) NULL COMMENT '来源系统：OA/SRM/U9/EXCEL/MANUAL';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='source_sheet_name') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN source_sheet_name VARCHAR(64) NULL COMMENT '来源 Excel sheet 名';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='source_row_no') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN source_row_no INT NULL COMMENT '来源 Excel 原始行号';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='source_batch_no') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN source_batch_no VARCHAR(64) NULL COMMENT '导入批次号';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='import_file_name') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN import_file_name VARCHAR(255) NULL COMMENT '导入文件名';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='imported_by') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN imported_by VARCHAR(64) NULL COMMENT '导入人';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='imported_at') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN imported_at DATETIME NULL COMMENT '导入时间';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='external_row_id') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN external_row_id VARCHAR(64) NULL COMMENT '外部行 id；固定采购价非 U9 按此字段幂等';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='process_status') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN process_status VARCHAR(64) NULL COMMENT '固定采购价流程状态';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='srm_doc_no') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN srm_doc_no VARCHAR(128) NULL COMMENT 'SRM 单据编号';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='material_category') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN material_category VARCHAR(64) NULL COMMENT '固定采购价物料类别';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='tax_rate') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN tax_rate DECIMAL(10,6) NULL COMMENT '税率';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='original_process_fee') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN original_process_fee DECIMAL(18,6) NULL COMMENT '原不含税加工费';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='original_process_fee_tax_included') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN original_process_fee_tax_included DECIMAL(18,6) NULL COMMENT '原含税加工费';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='original_tax_excluded_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN original_tax_excluded_price DECIMAL(18,6) NULL COMMENT '原不含税价格';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='original_tax_included_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN original_tax_included_price DECIMAL(18,6) NULL COMMENT '原含税价格';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='original_supplier_name') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN original_supplier_name VARCHAR(255) NULL COMMENT '原供方名称';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='current_process_fee') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN current_process_fee DECIMAL(18,6) NULL COMMENT '现不含税加工费';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='current_process_fee_tax_included') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN current_process_fee_tax_included DECIMAL(18,6) NULL COMMENT '现含税加工费';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='current_tax_excluded_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN current_tax_excluded_price DECIMAL(18,6) NULL COMMENT '现不含税价格；固定采购价主价格来源';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='current_tax_included_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN current_tax_included_price DECIMAL(18,6) NULL COMMENT '现含税价格';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='current_supplier_name') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN current_supplier_name VARCHAR(255) NULL COMMENT '现供方名称';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='change_amount') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN change_amount DECIMAL(18,6) NULL COMMENT '上涨额';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='change_rate') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN change_rate DECIMAL(18,6) NULL COMMENT '幅度';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='execution_period_text') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN execution_period_text VARCHAR(128) NULL COMMENT '执行日期原始文本';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='annual_usage_text') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN annual_usage_text VARCHAR(128) NULL COMMENT '预计年用量原始文本';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='applicant') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN applicant VARCHAR(64) NULL COMMENT '申请人';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='apply_dept') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN apply_dept VARCHAR(128) NULL COMMENT '申请部门';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='market_situation') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN market_situation TEXT NULL COMMENT '市场行情';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='similar_compare') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN similar_compare TEXT NULL COMMENT '类似物比较';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='approval_conclusion') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN approval_conclusion TEXT NULL COMMENT '结论';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='approval_type') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN approval_type VARCHAR(128) NULL COMMENT '审批表类型';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='business_division') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN business_division VARCHAR(255) NULL COMMENT '涉及事业部';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='general_manager_approved_at') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN general_manager_approved_at DATETIME NULL COMMENT '总经理批准时间';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='tracking_date') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN tracking_date DATE NULL COMMENT '板分法跟踪日期';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='print_flag') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN print_flag VARCHAR(32) NULL COMMENT '是否打印';
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='settle_reference_header') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN settle_reference_header VARCHAR(255) NULL COMMENT '结算固定价最后一列表头';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='settle_reference_text') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN settle_reference_text VARCHAR(128) NULL COMMENT '结算固定价最后一列非数字备注，例如“不用提供”';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='settle_reference_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN settle_reference_price DECIMAL(18,6) NULL COMMENT '结算固定价最后一列数字价格';
  END IF;
END$$
DELIMITER ;
CALL v92_extend_fixed_price_item();
DROP PROCEDURE v92_extend_fixed_price_item;

DROP PROCEDURE IF EXISTS v92_rebuild_fixed_price_indexes;
DELIMITER $$
CREATE PROCEDURE v92_rebuild_fixed_price_indexes()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.statistics
             WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
               AND index_name='uk_fixed_unique') THEN
    ALTER TABLE lp_price_fixed_item DROP INDEX uk_fixed_unique;
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
                   AND index_name='idx_fixed_source_external') THEN
    ALTER TABLE lp_price_fixed_item
      ADD INDEX idx_fixed_source_external (source_type, source_system, external_row_id);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
                   AND index_name='idx_fixed_source_material') THEN
    ALTER TABLE lp_price_fixed_item
      ADD INDEX idx_fixed_source_material (source_type, source_system, material_code);
  END IF;

  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item'
                   AND index_name='idx_fixed_import_batch') THEN
    ALTER TABLE lp_price_fixed_item
      ADD INDEX idx_fixed_import_batch (source_batch_no);
  END IF;
END$$
DELIMITER ;
CALL v92_rebuild_fixed_price_indexes();
DROP PROCEDURE v92_rebuild_fixed_price_indexes;

-- V92 结束
