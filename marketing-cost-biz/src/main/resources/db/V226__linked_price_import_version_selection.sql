-- 联动公式版本改为按正式导入顺序选择。
--
-- pricing_month 决定价格月份；同月同料号同供应商允许多次正式导入，
-- 当前版本由 created_at DESC, id DESC 决定。effective_from/effective_to
-- 仅保留为历史展示元数据，不再承担唯一性或当前版本选择职责。

DROP PROCEDURE IF EXISTS v226_drop_index_if_exists;
DELIMITER $$
CREATE PROCEDURE v226_drop_index_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128)
)
BEGIN
  IF EXISTS (
      SELECT 1
        FROM information_schema.statistics
       WHERE table_schema = DATABASE()
         AND table_name = p_table_name
         AND index_name = p_index_name
  ) THEN
    SET @v226_sql = CONCAT(
      'ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE v226_stmt FROM @v226_sql;
    EXECUTE v226_stmt;
    DEALLOCATE PREPARE v226_stmt;
  END IF;
END$$
DELIMITER ;

CALL v226_drop_index_if_exists('lp_price_linked_item', 'uk_linked_formula_version');
CALL v226_drop_index_if_exists('lp_price_linked_item', 'idx_linked_current_version_lookup');
DROP PROCEDURE IF EXISTS v226_drop_index_if_exists;

DROP PROCEDURE IF EXISTS v226_add_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v226_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
      SELECT 1
        FROM information_schema.statistics
       WHERE table_schema = DATABASE()
         AND table_name = p_table_name
         AND index_name = p_index_name
  ) THEN
    SET @v226_sql = p_index_ddl;
    PREPARE v226_stmt FROM @v226_sql;
    EXECUTE v226_stmt;
    DEALLOCATE PREPARE v226_stmt;
  END IF;
END$$
DELIMITER ;

CALL v226_add_index_if_not_exists(
  'lp_price_linked_item',
  'idx_linked_import_version_lookup',
  'ALTER TABLE `lp_price_linked_item`
     ADD KEY `idx_linked_import_version_lookup`
       (`business_unit_type`, `material_code`, `pricing_month`,
        `supplier_code`, `created_at`, `deleted`)'
);

DROP PROCEDURE IF EXISTS v226_add_index_if_not_exists;
