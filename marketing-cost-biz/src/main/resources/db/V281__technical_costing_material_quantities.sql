-- 补录材料沿用正式 BOM/取价/冻结成本链；保留数量精度及模块分类，不重算旧金额。
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_cost_run_part_item' AND column_name='technical_module_type'), 'SELECT 1', 'ALTER TABLE lp_cost_run_part_item ADD COLUMN technical_module_type VARCHAR(32) NULL COMMENT ''批准补录材料模块 PACKAGE/SOLDER''');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
ALTER TABLE lp_quote_effective_bom_node
  MODIFY COLUMN qty_per_parent DECIMAL(40,16) NOT NULL,
  MODIFY COLUMN qty_per_top DECIMAL(40,16) NOT NULL;
ALTER TABLE lp_bom_costing_row
  MODIFY COLUMN qty_per_parent DECIMAL(40,16) NULL,
  MODIFY COLUMN qty_per_top DECIMAL(40,16) NOT NULL;
ALTER TABLE lp_price_prepare_item
  MODIFY COLUMN quantity DECIMAL(40,16) NULL;
ALTER TABLE lp_cost_run_part_item
  MODIFY COLUMN qty DECIMAL(40,16) NULL;
ALTER TABLE lp_cost_run_trace_snapshot
  MODIFY COLUMN quantity DECIMAL(40,16) NULL;
