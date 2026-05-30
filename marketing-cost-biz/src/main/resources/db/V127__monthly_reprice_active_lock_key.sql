-- =============================================================================
-- V127: 月度调价业务单元并发锁兜底
-- -----------------------------------------------------------------------------
-- 说明：
--   第一阶段不新增通用锁表，仍然复用 lp_monthly_reprice_batch。
--   active_lock_key 是生成列：只有未结束状态才等于 business_unit_type；
--   终态为 NULL。MySQL 唯一索引允许多个 NULL，因此可以支持同业务单元多次顺序调价，
--   但同一时间只能存在一个未结束批次。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_column_if_not_exists_v127;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v127(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v127;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v127(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_column_if_not_exists_v127('lp_monthly_reprice_batch', 'active_lock_key',
  'VARCHAR(32) GENERATED ALWAYS AS (CASE WHEN `status` IN (''CREATED'', ''PREPARING'', ''RUNNING'', ''WAIT_CONFIRM'') THEN `business_unit_type` ELSE NULL END) STORED COMMENT ''未结束批次业务单元锁键'' AFTER `status`');

CALL add_index_if_not_exists_v127('lp_monthly_reprice_batch', 'uk_monthly_reprice_active_bu',
  'UNIQUE KEY uk_monthly_reprice_active_bu (active_lock_key)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v127;
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v127;
