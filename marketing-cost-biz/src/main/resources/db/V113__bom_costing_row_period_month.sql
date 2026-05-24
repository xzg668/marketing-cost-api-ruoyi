-- =============================================================================
-- V113: BOM 结算行增加期间字段，刷新时按 OA + 顶层产品 + 月份唯一
-- -----------------------------------------------------------------------------
-- as_of_date 仍表示 BOM 有效版本基准日；period_month 表示业务结算月份。
-- 同月多次刷新只保留最新 as_of_date 的一份明细，避免 BOM 明细数量叠加。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v113_add_column_if_not_exists $$
CREATE PROCEDURE v113_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DROP PROCEDURE IF EXISTS v113_add_index_if_not_exists $$
CREATE PROCEDURE v113_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v113_add_column_if_not_exists(
  'lp_bom_costing_row',
  'period_month',
  'VARCHAR(7) NOT NULL DEFAULT '''' COMMENT ''结算期间 yyyy-MM，同 OA+产品+期间唯一刷新'' AFTER built_at'
);

UPDATE lp_bom_costing_row
   SET period_month = DATE_FORMAT(as_of_date, '%Y-%m')
 WHERE period_month = ''
   AND as_of_date IS NOT NULL;

-- 清理历史同月多次刷新造成的叠加明细：同 OA + 产品 + 月份只保留最新 as_of_date。
DELETE cr
  FROM lp_bom_costing_row cr
  JOIN (
    SELECT oa_no, top_product_code, period_month, MAX(as_of_date) AS keep_as_of_date
      FROM lp_bom_costing_row
     WHERE period_month <> ''
     GROUP BY oa_no, top_product_code, period_month
    HAVING COUNT(DISTINCT as_of_date) > 1
  ) latest
    ON latest.oa_no = cr.oa_no
   AND latest.top_product_code = cr.top_product_code
   AND latest.period_month = cr.period_month
 WHERE cr.as_of_date < latest.keep_as_of_date;

CALL v113_add_index_if_not_exists(
  'lp_bom_costing_row',
  'idx_bom_costing_oa_top_period',
  'INDEX idx_bom_costing_oa_top_period (oa_no, top_product_code, period_month)'
);

DROP PROCEDURE IF EXISTS v113_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v113_add_index_if_not_exists;
