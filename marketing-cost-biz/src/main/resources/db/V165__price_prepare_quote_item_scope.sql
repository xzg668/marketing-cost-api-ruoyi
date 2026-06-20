-- =============================================================================
-- V165: 价格准备补齐报价产品行和价格类型确认维度
-- -----------------------------------------------------------------------------
-- QWB-01-03：价格准备批次、明细、缺口按 OA 产品行隔离，并关联价格类型确认批次。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v165_add_column_if_not_exists $$
CREATE PROCEDURE v165_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v165_add_index_if_not_exists $$
CREATE PROCEDURE v165_add_index_if_not_exists(
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

DROP PROCEDURE IF EXISTS v165_drop_index_if_exists $$
CREATE PROCEDURE v165_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' DROP INDEX ', p_index_name);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'top_product_code',
  'VARCHAR(64) DEFAULT NULL COMMENT ''顶层产品料号'' AFTER oa_form_item_id'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_batch',
  'price_type_confirm_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''价格类型确认批次号'' AFTER top_product_code'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_item',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_item',
  'price_type_confirm_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''价格类型确认批次号'' AFTER period_month'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_item',
  'price_type_confirm_item_id',
  'BIGINT DEFAULT NULL COMMENT ''价格类型确认明细ID'' AFTER price_type_confirm_no'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'oa_form_item_id',
  'BIGINT DEFAULT NULL COMMENT ''OA产品明细行ID'' AFTER oa_no'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'price_type_confirm_no',
  'VARCHAR(64) DEFAULT NULL COMMENT ''价格类型确认批次号'' AFTER period_month'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'price_type_confirm_item_id',
  'BIGINT DEFAULT NULL COMMENT ''价格类型确认明细ID'' AFTER price_type_confirm_no'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'action_type',
  'VARCHAR(32) DEFAULT NULL COMMENT ''缺口处理动作类型'' AFTER price_type_confirm_item_id'
);

CALL v165_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'action_target',
  'VARCHAR(255) DEFAULT NULL COMMENT ''缺口处理目标'' AFTER action_type'
);

UPDATE lp_price_prepare_batch b
JOIN lp_price_prepare_item i ON i.prepare_no = b.prepare_no
   SET b.top_product_code = i.top_product_code
 WHERE b.top_product_code IS NULL
   AND i.top_product_code IS NOT NULL;

UPDATE lp_price_prepare_item i
JOIN lp_price_prepare_batch b ON b.prepare_no = i.prepare_no
   SET i.oa_form_item_id = b.oa_form_item_id,
       i.price_type_confirm_no = b.price_type_confirm_no
 WHERE i.oa_form_item_id IS NULL
   AND (b.oa_form_item_id IS NOT NULL OR b.price_type_confirm_no IS NOT NULL);

UPDATE lp_price_prepare_gap g
JOIN lp_price_prepare_batch b ON b.prepare_no = g.prepare_no
   SET g.oa_form_item_id = b.oa_form_item_id,
       g.price_type_confirm_no = b.price_type_confirm_no
 WHERE g.oa_form_item_id IS NULL
   AND (b.oa_form_item_id IS NOT NULL OR b.price_type_confirm_no IS NOT NULL);

CALL v165_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_row');
CALL v165_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_row',
  'UNIQUE KEY uk_price_prepare_item_row (prepare_no, oa_form_item_id, bom_row_id, material_code)'
);

CALL v165_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_current');
CALL v165_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_current',
  'UNIQUE KEY uk_price_prepare_item_current (oa_no, oa_form_item_id, period_month, top_product_code, material_code)'
);

CALL v165_drop_index_if_exists('lp_price_prepare_gap', 'uk_price_prepare_gap_current');
CALL v165_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'uk_price_prepare_gap_current',
  'UNIQUE KEY uk_price_prepare_gap_current (oa_no, oa_form_item_id, period_month, top_product_code, material_code, gap_material_code, gap_type, item_type)'
);

CALL v165_add_index_if_not_exists(
  'lp_price_prepare_batch',
  'idx_pp_batch_item_scope',
  'KEY idx_pp_batch_item_scope (oa_no, oa_form_item_id, top_product_code, period_month)'
);

CALL v165_add_index_if_not_exists(
  'lp_price_prepare_item',
  'idx_pp_item_item_scope',
  'KEY idx_pp_item_item_scope (oa_no, oa_form_item_id, top_product_code)'
);

CALL v165_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'idx_pp_gap_item_scope',
  'KEY idx_pp_gap_item_scope (oa_no, oa_form_item_id, top_product_code)'
);

CALL v165_add_index_if_not_exists(
  'lp_price_prepare_item',
  'idx_pp_item_confirm',
  'KEY idx_pp_item_confirm (price_type_confirm_no)'
);

CALL v165_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'idx_pp_gap_confirm',
  'KEY idx_pp_gap_confirm (price_type_confirm_no)'
);

DROP PROCEDURE IF EXISTS v165_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v165_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v165_drop_index_if_exists;
