-- V125: 三项费用新口径不再保留导入原始期间，只按 period_year 匹配。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v125_drop_column_if_exists;

DELIMITER $$

CREATE PROCEDURE v125_drop_column_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP COLUMN `', p_column_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v125_drop_column_if_exists('lp_three_expense_rate', 'raw_period');

DROP PROCEDURE IF EXISTS v125_drop_column_if_exists;
