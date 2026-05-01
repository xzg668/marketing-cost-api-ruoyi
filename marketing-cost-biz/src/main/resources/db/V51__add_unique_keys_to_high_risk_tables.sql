-- =============================================================================
-- V51  给 3 张高风险价格表补 UNIQUE KEY                                    2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   V50 修了 lp_price_linked_calc_item 的 UK 后，扫描发现还有 3 张高风险
--   "频繁 import/refresh" 的表没有业务唯一键，全靠应用层去重，是隐患。
--
--   dry-run 验证：3 张表当前 0 重复，可安全直接加 UK：
--     - lp_price_linked_item   22 行 / 0 重复
--     - lp_finance_base_price  54 行 / 0 重复
--     - lp_material_price_type 29 行 / 0 重复
--
--   lp_price_range_item 没 pricing_month 字段、UK 设计需跟业务对齐 → 本脚本不动。
--
-- 幂等：用 information_schema 检查 UK 不存在再加
-- =============================================================================

-- 1) lp_price_linked_item
DROP PROCEDURE IF EXISTS v51_uk_linked;
DELIMITER $$
CREATE PROCEDURE v51_uk_linked()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE()
                   AND table_name='lp_price_linked_item'
                   AND index_name='uk_linked_month_mat_supp_bu') THEN
    ALTER TABLE lp_price_linked_item
      ADD UNIQUE KEY uk_linked_month_mat_supp_bu (pricing_month, material_code, supplier_code, business_unit_type);
  END IF;
END$$
DELIMITER ;
CALL v51_uk_linked();
DROP PROCEDURE v51_uk_linked;

-- 2) lp_finance_base_price
DROP PROCEDURE IF EXISTS v51_uk_finance;
DELIMITER $$
CREATE PROCEDURE v51_uk_finance()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE()
                   AND table_name='lp_finance_base_price'
                   AND index_name='uk_finance_month_short_source_bu') THEN
    ALTER TABLE lp_finance_base_price
      ADD UNIQUE KEY uk_finance_month_short_source_bu (price_month, short_name, price_source, business_unit_type);
  END IF;
END$$
DELIMITER ;
CALL v51_uk_finance();
DROP PROCEDURE v51_uk_finance;

-- 3) lp_material_price_type
DROP PROCEDURE IF EXISTS v51_uk_route;
DELIMITER $$
CREATE PROCEDURE v51_uk_route()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE()
                   AND table_name='lp_material_price_type'
                   AND index_name='uk_route_period_mat_type_bu') THEN
    ALTER TABLE lp_material_price_type
      ADD UNIQUE KEY uk_route_period_mat_type_bu (period, material_code, price_type, business_unit_type);
  END IF;
END$$
DELIMITER ;
CALL v51_uk_route();
DROP PROCEDURE v51_uk_route;
