-- =====================================================================
-- V200: 报价 BOM 标准/替代选择唯一键补齐组织和业务单元隔离
--
-- V199 已建立选择表。本迁移仅重建两个唯一索引：
-- 1. 同一完整报价作用域、同一替代组、同一版本只能有一条；
-- 2. 同一完整报价作用域、同一替代组只能有一条当前选择。
--
-- 不新增表，不修改选择记录内容，不触碰 BOM 或报价结算行。
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v200_drop_index_if_exists;
DELIMITER //
CREATE PROCEDURE v200_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @v200_drop_index_ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`'
    );
    PREPARE v200_drop_index_stmt FROM @v200_drop_index_ddl;
    EXECUTE v200_drop_index_stmt;
    DEALLOCATE PREPARE v200_drop_index_stmt;
  END IF;
END//
DELIMITER ;

CALL v200_drop_index_if_exists(
  'lp_quote_bom_alternative_selection',
  'uk_quote_alt_selection_version'
);
CALL v200_drop_index_if_exists(
  'lp_quote_bom_alternative_selection',
  'uk_quote_alt_selection_current'
);

ALTER TABLE `lp_quote_bom_alternative_selection`
  ADD UNIQUE KEY `uk_quote_alt_selection_version` (
    `oa_no`,
    `oa_form_item_id`,
    `top_product_code`,
    `period_month`,
    `price_org_code`,
    `business_unit_type`,
    `alternative_group_key`,
    `selection_version`
  ),
  ADD UNIQUE KEY `uk_quote_alt_selection_current` (
    `oa_no`,
    `oa_form_item_id`,
    `top_product_code`,
    `period_month`,
    `price_org_code`,
    `business_unit_type`,
    `alternative_group_key`,
    `current_slot`
  );

DROP PROCEDURE IF EXISTS v200_drop_index_if_exists;
