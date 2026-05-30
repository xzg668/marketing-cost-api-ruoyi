-- T4：联动公式改为按 effective_from 版本化。
-- 旧唯一键只允许同月同料号同供应商同业务单元一条记录，会阻止公式变化时插入新版本。

DROP PROCEDURE IF EXISTS v151_drop_index_if_exists;
DELIMITER $$
CREATE PROCEDURE v151_drop_index_if_exists(
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
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v151_drop_index_if_exists('lp_price_linked_item', 'uk_linked_month_mat_supp_bu');
DROP PROCEDURE IF EXISTS v151_drop_index_if_exists;

DROP PROCEDURE IF EXISTS v151_add_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v151_add_index_if_not_exists(
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
    SET @sql = p_index_ddl;
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v151_add_index_if_not_exists(
  'lp_price_linked_item',
  'uk_linked_formula_version',
  'ALTER TABLE `lp_price_linked_item`
     ADD UNIQUE KEY `uk_linked_formula_version`
       (`pricing_month`, `material_code`, `supplier_code`, `spec_model`,
        `business_unit_type`, `effective_from`)'
);

CALL v151_add_index_if_not_exists(
  'lp_price_linked_item',
  'idx_linked_current_version_lookup',
  'ALTER TABLE `lp_price_linked_item`
     ADD KEY `idx_linked_current_version_lookup`
       (`pricing_month`, `material_code`, `supplier_code`, `spec_model`,
        `business_unit_type`, `effective_to`, `deleted`)'
);

DROP PROCEDURE IF EXISTS v151_add_index_if_not_exists;
