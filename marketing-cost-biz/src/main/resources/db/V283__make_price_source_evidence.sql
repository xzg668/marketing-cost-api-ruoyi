-- 保存制造件原料和废料的实际价格来源，正式核算按原补录任务核验，历史未知来源保持 NULL。
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='raw_source_price_record_id'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN raw_source_price_record_id BIGINT NULL');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='raw_source_price_batch_no'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN raw_source_price_batch_no VARCHAR(128) NULL');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='scrap_source_price_record_id'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN scrap_source_price_record_id BIGINT NULL');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
SET @tw18_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_make_part_price_calc_row' AND column_name='scrap_source_price_batch_no'), 'SELECT 1', 'ALTER TABLE lp_make_part_price_calc_row ADD COLUMN scrap_source_price_batch_no VARCHAR(128) NULL');
PREPARE tw18_stmt FROM @tw18_ddl;
EXECUTE tw18_stmt;
DEALLOCATE PREPARE tw18_stmt;
