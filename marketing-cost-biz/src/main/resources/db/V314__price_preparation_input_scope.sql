-- PRC-07：计算结果按取价组织隔离，价格准备保存实际读快照的输入修订。
-- 历史未知组织保留空串，旧冻结业务结果不回写。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS v314_price_scope;
DELIMITER //
CREATE PROCEDURE v314_price_scope()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND column_name='price_org_code') THEN
    ALTER TABLE lp_price_linked_calc_item ADD COLUMN price_org_code VARCHAR(32) NOT NULL DEFAULT '';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND column_name='result_revision') THEN
    ALTER TABLE lp_price_linked_calc_item ADD COLUMN result_revision BIGINT NOT NULL DEFAULT 0;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_prepare_batch' AND column_name='source_revision') THEN
    ALTER TABLE lp_price_prepare_batch ADD COLUMN source_revision VARCHAR(64) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_quote_scene_as_of_factor'
      AND column_name='price_org_code') THEN
    ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_quote_scene_as_of_factor,
      ADD UNIQUE KEY uk_pl_calc_quote_scene_as_of_factor
      (business_unit_type,calc_scene,factor_source,oa_no,item_code,pricing_month,price_as_of_time,source_kind,price_org_code);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_adjust_scene'
      AND column_name='price_org_code') THEN
    ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_adjust_scene,
      ADD UNIQUE KEY uk_pl_calc_adjust_scene
      (business_unit_type,calc_scene,adjust_batch_id,item_code,pricing_month,source_kind,price_org_code);
  END IF;
END//
DELIMITER ;
CALL v314_price_scope();
DROP PROCEDURE v314_price_scope;
