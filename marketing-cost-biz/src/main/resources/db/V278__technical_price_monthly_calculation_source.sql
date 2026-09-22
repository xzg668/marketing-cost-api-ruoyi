-- 月调同一批次允许公共计算与补录计算各留一份，避免补录结果覆盖公共结果。
SET @tw15_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_adjust_scene' AND column_name='source_kind'), 'SELECT 1', 'ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_adjust_scene, ADD UNIQUE INDEX uk_pl_calc_adjust_scene (business_unit_type,calc_scene,adjust_batch_id,item_code,pricing_month,source_kind)');
PREPARE tw15_stmt FROM @tw15_ddl;
EXECUTE tw15_stmt;
DEALLOCATE PREPARE tw15_stmt;
