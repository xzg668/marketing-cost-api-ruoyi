DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v78(
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
DELIMITER ;

CALL add_column_if_not_exists_v78('lp_factor_row_ref', 'factor_name',
  'VARCHAR(512) DEFAULT NULL COMMENT ''导入时价表影响因素名称快照'' AFTER factor_seq_no');

CALL add_column_if_not_exists_v78('lp_factor_row_ref', 'original_price',
  'DECIMAL(20,6) DEFAULT NULL COMMENT ''导入时原价格快照'' AFTER price');

CALL add_column_if_not_exists_v78('lp_factor_row_ref', 'unit',
  'VARCHAR(32) DEFAULT NULL COMMENT ''导入时单位快照'' AFTER original_price');

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v78;
