-- =============================================================================
-- V106: 包装组件快照补充 BOM 母件底数/用量追溯
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 包装组件结构来自“顶层产品 -> 包装父料号”的主制造 BOM 行。
--   2. 该父行的子项用量、累计用量、母件底数需要随月度快照锁定。
--   3. 子件明细保留包装父料号 -> 子件 BOM 行的用量和母件底数，便于复算追溯。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS add_pkg_col_v106 $$
CREATE PROCEDURE add_pkg_col_v106(
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

DELIMITER ;

CALL add_pkg_col_v106(
  'lp_package_component_snapshot',
  'package_qty_per_parent',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号相对直接父用量'' AFTER source_path'
);

CALL add_pkg_col_v106(
  'lp_package_component_snapshot',
  'package_qty_per_top',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号相对顶层产品累计用量'' AFTER package_qty_per_parent'
);

CALL add_pkg_col_v106(
  'lp_package_component_snapshot',
  'package_parent_base_qty',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''顶层/直接父到包装父料号BOM行母件底数'' AFTER package_qty_per_top'
);

CALL add_pkg_col_v106(
  'lp_package_component_snapshot_detail',
  'child_parent_base_qty',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号到子件BOM行母件底数'' AFTER qty_per_top'
);

CALL add_pkg_col_v106(
  'lp_package_component_price',
  'package_qty_per_parent',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号相对直接父用量'' AFTER total_price'
);

CALL add_pkg_col_v106(
  'lp_package_component_price',
  'package_qty_per_top',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号相对顶层产品累计用量'' AFTER package_qty_per_parent'
);

CALL add_pkg_col_v106(
  'lp_package_component_price',
  'package_parent_base_qty',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''顶层/直接父到包装父料号BOM行母件底数'' AFTER package_qty_per_top'
);

CALL add_pkg_col_v106(
  'lp_package_component_price_detail',
  'child_parent_base_qty',
  'DECIMAL(20,8) DEFAULT NULL COMMENT ''包装父料号到子件BOM行母件底数'' AFTER qty_per_parent'
);

DROP PROCEDURE IF EXISTS add_pkg_col_v106;
