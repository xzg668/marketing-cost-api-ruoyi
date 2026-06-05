-- =============================================================================
-- V155: 制造件价格生成行记录无废料人工确认标记
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 不把“确认无废料”写成虚拟 scrap_code，避免污染真实 CMS 废料料号。
--   2. 确认后 scrap_code 仍为空，依靠 no_scrap_confirmed + no_scrap_confirmation_id 留痕。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v155_add_column_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v155_add_column_if_not_exists(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def    VARCHAR(1000)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = p_table
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL v155_add_column_if_not_exists(
    'lp_make_part_price_calc_row',
    'no_scrap_confirmed',
    "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工确认无废料并按0抵扣：1 是，0 否' AFTER `scrap_unit_price`");

CALL v155_add_column_if_not_exists(
    'lp_make_part_price_calc_row',
    'no_scrap_confirmation_id',
    "BIGINT DEFAULT NULL COMMENT '无废料人工确认记录ID，关联 lp_make_part_no_scrap_confirmation.id' AFTER `no_scrap_confirmed`");

DROP PROCEDURE IF EXISTS v155_add_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v155_add_index_if_not_exists(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_ddl   VARCHAR(1000)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = p_table
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @ddl = p_ddl;
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL v155_add_index_if_not_exists(
    'lp_make_part_price_calc_row',
    'idx_make_calc_no_scrap_confirmation',
    'ALTER TABLE `lp_make_part_price_calc_row` ADD KEY `idx_make_calc_no_scrap_confirmation` (`no_scrap_confirmation_id`)');

DROP PROCEDURE IF EXISTS v155_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v155_add_index_if_not_exists;
