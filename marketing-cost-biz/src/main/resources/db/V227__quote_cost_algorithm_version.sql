-- =============================================================================
-- V227: 成本成功版本固化算法版本
--
-- 旧成功版本统一标记为 LEGACY。应用当前算法版本默认为 COST_V1，因此旧版本不会被
-- 当作“当前输入已核算”直接复用；用新算法成功核算一次后，后续相同输入才会继续复用。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v227_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v227_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v227_add_column_if_not_exists(
  'lp_quote_cost_run_version',
  'algorithm_version',
  '`algorithm_version` VARCHAR(64) NOT NULL DEFAULT ''LEGACY'' COMMENT ''成本算法版本'' AFTER `input_fingerprint`'
);

DROP PROCEDURE IF EXISTS v227_add_column_if_not_exists;
