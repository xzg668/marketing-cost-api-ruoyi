-- V133: 月度调价发起不再依赖影响因素调价批次。
-- 月度调价按业务单元 + 月份创建批次，并固化 price_as_of_time；全价格源按该时点重算。

DROP PROCEDURE IF EXISTS v133_modify_column_if_exists;
DELIMITER $$
CREATE PROCEDURE v133_modify_column_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = p_table_name
       AND column_name = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` MODIFY COLUMN `', p_column_name, '` ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v133_modify_column_if_exists(
  'lp_monthly_reprice_batch',
  'adjust_batch_id',
  'BIGINT NULL COMMENT ''兼容旧链路：可选影响因素调价批次ID；新月度调价按 price_as_of_time 全价格源重算''');

DROP PROCEDURE IF EXISTS v133_modify_column_if_exists;
