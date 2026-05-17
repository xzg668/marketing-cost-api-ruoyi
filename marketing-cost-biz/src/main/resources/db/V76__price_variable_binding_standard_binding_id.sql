DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v76(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND column_name = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//

CREATE PROCEDURE add_index_if_not_exists_v76(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND index_name = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_not_exists_v76('lp_price_variable_binding', 'standard_binding_id',
  'BIGINT NULL COMMENT ''料号历史校验关系 id，来源 lp_material_factor_binding_std.id'' AFTER factor_upload_batch_id');

CALL add_index_if_not_exists_v76('lp_price_variable_binding', 'idx_binding_standard_binding',
  'KEY idx_binding_standard_binding (standard_binding_id)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v76;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v76;
