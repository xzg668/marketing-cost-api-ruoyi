-- =============================================================================
-- V219: 主供应商取价与历史价沿用底稿
--
-- 只固化一次计算必需的来源证据，不复制价格主表整行。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v219_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v219_add_column_if_not_exists(
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

CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'source_price_record_id',
  '`source_price_record_id` BIGINT DEFAULT NULL COMMENT ''实际使用的联动价主数据ID'' AFTER `price_as_of_time`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'source_price_batch_no',
  '`source_price_batch_no` VARCHAR(64) DEFAULT NULL COMMENT ''联动价来源批次'' AFTER `source_price_record_id`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'supplier_name',
  '`supplier_name` VARCHAR(180) DEFAULT NULL COMMENT ''计算主供应商名称'' AFTER `source_price_batch_no`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'supplier_code',
  '`supplier_code` VARCHAR(64) DEFAULT NULL COMMENT ''计算主供应商代码'' AFTER `supplier_name`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'supply_ratio',
  '`supply_ratio` DECIMAL(18,6) DEFAULT NULL COMMENT ''计算时主供供货比例'' AFTER `supplier_code`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'supply_ratio_record_id',
  '`supply_ratio_record_id` BIGINT DEFAULT NULL COMMENT ''供货比例来源记录ID'' AFTER `supply_ratio`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'source_effective_from',
  '`source_effective_from` DATE DEFAULT NULL COMMENT ''价格来源生效开始日'' AFTER `supply_ratio_record_id`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'source_effective_to',
  '`source_effective_to` DATE DEFAULT NULL COMMENT ''价格来源审批有效截止日'' AFTER `source_effective_from`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'carried_forward',
  '`carried_forward` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=沿用已过审批有效期的最近价格'' AFTER `source_effective_to`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'warning_message',
  '`warning_message` VARCHAR(500) DEFAULT NULL COMMENT ''非阻断取价提醒'' AFTER `carried_forward`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_linked_calc_item', 'failure_code',
  '`failure_code` VARCHAR(64) DEFAULT NULL COMMENT ''结构化计算失败码'' AFTER `warning_message`'
);

CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'price_type',
  '`price_type` VARCHAR(32) DEFAULT NULL COMMENT ''命中的价格类型'' AFTER `price_source`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'source_price_record_id',
  '`source_price_record_id` BIGINT DEFAULT NULL COMMENT ''正式价格源记录ID'' AFTER `result_ref_id`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'source_price_batch_no',
  '`source_price_batch_no` VARCHAR(64) DEFAULT NULL COMMENT ''正式价格源批次'' AFTER `source_price_record_id`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'supplier_name',
  '`supplier_name` VARCHAR(180) DEFAULT NULL COMMENT ''最终主供应商名称'' AFTER `source_price_batch_no`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'supplier_code',
  '`supplier_code` VARCHAR(64) DEFAULT NULL COMMENT ''最终主供应商代码'' AFTER `supplier_name`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'supply_ratio',
  '`supply_ratio` DECIMAL(18,6) DEFAULT NULL COMMENT ''最终主供供货比例'' AFTER `supplier_code`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'supply_ratio_record_id',
  '`supply_ratio_record_id` BIGINT DEFAULT NULL COMMENT ''供货比例来源记录ID'' AFTER `supply_ratio`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'source_effective_from',
  '`source_effective_from` DATE DEFAULT NULL COMMENT ''价格来源生效开始日'' AFTER `supply_ratio_record_id`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'source_effective_to',
  '`source_effective_to` DATE DEFAULT NULL COMMENT ''价格来源审批有效截止日'' AFTER `source_effective_from`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'carried_forward',
  '`carried_forward` TINYINT NOT NULL DEFAULT 0 COMMENT ''1=沿用历史审批价'' AFTER `source_effective_to`'
);
CALL v219_add_column_if_not_exists(
  'lp_price_prepare_item', 'warning_message',
  '`warning_message` VARCHAR(500) DEFAULT NULL COMMENT ''非阻断取价提醒'' AFTER `carried_forward`'
);

CALL v219_add_column_if_not_exists(
  'lp_price_prepare_gap', 'reason_code',
  '`reason_code` VARCHAR(64) DEFAULT NULL COMMENT ''结构化缺口原因'' AFTER `gap_type`'
);

DROP PROCEDURE IF EXISTS v219_add_column_if_not_exists;
