-- 同一报价时点的公共计算与补录计算分别留存，不覆盖彼此的价格及冻结追溯。
SET @tw15_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_price_linked_calc_item' AND column_name='source_kind'), 'SELECT 1', 'ALTER TABLE lp_price_linked_calc_item ADD COLUMN source_kind VARCHAR(32) NOT NULL DEFAULT ''PUBLIC''');
PREPARE tw15_stmt FROM @tw15_ddl;
EXECUTE tw15_stmt;
DEALLOCATE PREPARE tw15_stmt;
SET @tw15_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_quote_scene_as_of_factor' AND column_name='source_kind'), 'SELECT 1', 'ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_quote_scene_as_of_factor, ADD UNIQUE INDEX uk_pl_calc_quote_scene_as_of_factor (business_unit_type,calc_scene,factor_source,oa_no,item_code,pricing_month,price_as_of_time,source_kind)');
PREPARE tw15_stmt FROM @tw15_ddl;
EXECUTE tw15_stmt;
DEALLOCATE PREPARE tw15_stmt;
