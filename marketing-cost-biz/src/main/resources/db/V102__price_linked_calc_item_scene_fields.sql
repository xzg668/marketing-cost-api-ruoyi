-- =============================================================================
-- V102  联动价 calc_item 增加场景上下文字段                         2026-05-20
--
-- 背景：
--   原 lp_price_linked_calc_item 只按 oa_no + item_code + business_unit_type
--   保存一个结果。月度调价接入后，同一 OA/料号在正常报价(QUOTE)和月度调价
--   (MONTHLY_ADJUST) 下变量来源不同，必须能并存，不能互相覆盖或误读。
--
-- 兼容策略：
--   1) 历史数据全部回填为 QUOTE + OA_LOCKED；
--   2) pricing_month 对历史数据用空串兜底，避免 MySQL UNIQUE KEY 中 NULL 破坏去重；
--   3) 先加新场景唯一键，再移除 V50 的旧唯一键 uk_pl_calc_oa_item_bu；
--   4) Resolver/refresh 在后续任务完成前固定读取 QUOTE，保持现有页面兼容。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v102_add_column_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v102_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v102_add_index_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v102_add_index_if_not_exists(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_def   VARCHAR(1000)
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
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD ', p_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

DROP PROCEDURE IF EXISTS v102_drop_index_if_exists;
DELIMITER $$
CREATE PROCEDURE v102_drop_index_if_exists(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND index_name = p_index
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', p_index, '`');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'calc_scene',
    "VARCHAR(32) NOT NULL DEFAULT 'QUOTE' COMMENT '计算场景：QUOTE 正常报价 / MONTHLY_ADJUST 月度调价' AFTER `business_unit_type`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'pricing_month',
    "VARCHAR(7) NOT NULL DEFAULT '' COMMENT '价格月份 YYYY-MM；历史 QUOTE 数据为空串兜底' AFTER `calc_scene`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'adjust_batch_id',
    "BIGINT DEFAULT NULL COMMENT '月度调价批次 ID；QUOTE 场景为空' AFTER `pricing_month`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'factor_source',
    "VARCHAR(32) NOT NULL DEFAULT 'OA_LOCKED' COMMENT '变量来源：OA_LOCKED / MONTHLY_FACTOR / ADJUST_BATCH' AFTER `adjust_batch_id`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'calc_fingerprint',
    "VARCHAR(128) DEFAULT NULL COMMENT '输入指纹，用于判断联动价结果是否过期' AFTER `factor_source`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'calc_status',
    "VARCHAR(16) NOT NULL DEFAULT 'OK' COMMENT '计算状态：OK / FAILED' AFTER `calc_fingerprint`");

CALL v102_add_column_if_not_exists(
    'lp_price_linked_calc_item',
    'calc_message',
    "VARCHAR(500) DEFAULT NULL COMMENT '计算失败或跳过说明' AFTER `calc_status`");

UPDATE lp_price_linked_calc_item
SET calc_scene = 'QUOTE'
WHERE calc_scene IS NULL OR calc_scene = '';

UPDATE lp_price_linked_calc_item
SET pricing_month = ''
WHERE pricing_month IS NULL;

UPDATE lp_price_linked_calc_item
SET factor_source = 'OA_LOCKED'
WHERE factor_source IS NULL OR factor_source = '';

UPDATE lp_price_linked_calc_item
SET calc_status = 'OK'
WHERE calc_status IS NULL OR calc_status = '';

UPDATE lp_price_linked_calc_item
SET business_unit_type = 'COMMERCIAL'
WHERE business_unit_type IS NULL OR business_unit_type = '';

-- 新唯一键创建前兜底清重：保留同业务上下文下 id 最大的最新结果。
DELETE FROM lp_price_linked_calc_item
WHERE id IN (
  SELECT id FROM (
    SELECT id,
           ROW_NUMBER() OVER (
             PARTITION BY business_unit_type, calc_scene, oa_no, item_code, pricing_month
             ORDER BY id DESC
           ) AS rn
    FROM lp_price_linked_calc_item
    WHERE calc_scene = 'QUOTE'
  ) t WHERE t.rn > 1
);

CALL v102_add_index_if_not_exists(
    'lp_price_linked_calc_item',
    'uk_pl_calc_quote_scene',
    'UNIQUE KEY `uk_pl_calc_quote_scene` (`business_unit_type`, `calc_scene`, `oa_no`, `item_code`, `pricing_month`)');

CALL v102_add_index_if_not_exists(
    'lp_price_linked_calc_item',
    'uk_pl_calc_adjust_scene',
    'UNIQUE KEY `uk_pl_calc_adjust_scene` (`business_unit_type`, `calc_scene`, `adjust_batch_id`, `item_code`, `pricing_month`)');

CALL v102_add_index_if_not_exists(
    'lp_price_linked_calc_item',
    'idx_pl_calc_fingerprint',
    'KEY `idx_pl_calc_fingerprint` (`calc_scene`, `calc_fingerprint`)');

CALL v102_drop_index_if_exists('lp_price_linked_calc_item', 'uk_pl_calc_oa_item_bu');

DROP PROCEDURE IF EXISTS v102_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v102_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v102_drop_index_if_exists;
