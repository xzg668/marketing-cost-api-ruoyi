-- 制造件计算保留结算节点身份，同料号在不同报价产品/图库节点分别计算，历史未知来源保持 NULL。
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='source_costing_row_id'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN source_costing_row_id BIGINT NULL COMMENT ''实际报价结算节点ID''');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='source_costing_row_key'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN source_costing_row_key BIGINT GENERATED ALWAYS AS (IFNULL(source_costing_row_id,0)) STORED');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND index_name='uk_make_part_price_current_as_of_scene' AND column_name='source_costing_row_key'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row DROP INDEX uk_make_part_price_current_as_of_scene, ADD UNIQUE INDEX uk_make_part_price_current_as_of_scene (oa_no,pricing_month,price_as_of_time,price_scenario_type,parent_material_no,child_material_no,scrap_code,source_costing_row_key)');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
