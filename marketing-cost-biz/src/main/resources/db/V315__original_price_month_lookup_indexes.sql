-- PRC-10性能复验：选月只需范围、状态和月份，避免扫描原稿JSON所在的数据页。
-- 仅新增覆盖索引，不改任何价格、历史版本或唯一键。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS v315_original_month_indexes;
DELIMITER //
CREATE PROCEDURE v315_original_month_indexes()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_item' AND index_name='idx_linked_scope_month') THEN
    ALTER TABLE lp_price_linked_item ADD INDEX idx_linked_scope_month
      (business_unit_type,source_kind,deleted,org_code,pricing_month,rule_status);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_range_factor_rule' AND index_name='idx_range_scope_month') THEN
    ALTER TABLE lp_price_range_factor_rule ADD INDEX idx_range_scope_month
      (business_unit_type,org_code,pricing_month,rule_status);
  END IF;
END//
DELIMITER ;
CALL v315_original_month_indexes();
DROP PROCEDURE v315_original_month_indexes;
