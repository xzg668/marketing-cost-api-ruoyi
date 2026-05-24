-- =============================================================================
-- V110: 价格准备去批次化和当前最终价唯一键
-- -----------------------------------------------------------------------------
-- 说明：
--   1. PPR-11 起业务主链路不再依赖价格准备批次号。
--   2. 当前结果按 OA + 顶层产品 + 料号等业务键覆盖更新。
--   3. 历史追溯后续进入日志表，本迁移只保证当前结果不重复。
-- =============================================================================

SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS v110_add_column_if_not_exists $$
CREATE PROCEDURE v110_add_column_if_not_exists(
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

DROP PROCEDURE IF EXISTS v110_drop_index_if_exists $$
CREATE PROCEDURE v110_drop_index_if_exists(
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

DROP PROCEDURE IF EXISTS v110_add_index_if_not_exists $$
CREATE PROCEDURE v110_add_index_if_not_exists(
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

CALL v110_add_column_if_not_exists(
  'lp_package_component_price',
  'oa_no',
  'VARCHAR(64) NOT NULL DEFAULT '''' COMMENT ''OA单号，PPR-11 当前价格唯一口径'' AFTER period_month'
);

UPDATE lp_package_component_price p
LEFT JOIN lp_package_component_snapshot s ON s.id = p.snapshot_id
   SET p.oa_no = COALESCE(NULLIF(p.oa_no, ''), NULLIF(s.source_oa_no, ''), ''),
       p.source_top_product_code = COALESCE(NULLIF(p.source_top_product_code, ''), s.source_top_product_code, '')
 WHERE p.oa_no IS NULL
    OR p.oa_no = ''
    OR p.source_top_product_code IS NULL;

UPDATE lp_price_prepare_item
   SET prepare_no = COALESCE(prepare_no, ''),
       oa_no = COALESCE(oa_no, ''),
       top_product_code = COALESCE(top_product_code, ''),
       material_code = COALESCE(material_code, '')
 WHERE prepare_no IS NULL
    OR oa_no IS NULL
    OR top_product_code IS NULL
    OR material_code IS NULL;

UPDATE lp_price_prepare_gap
   SET prepare_no = COALESCE(prepare_no, ''),
       oa_no = COALESCE(oa_no, ''),
       top_product_code = COALESCE(top_product_code, ''),
       material_code = COALESCE(material_code, ''),
       gap_material_code = COALESCE(gap_material_code, ''),
       gap_type = COALESCE(gap_type, ''),
       item_type = COALESCE(item_type, '')
 WHERE prepare_no IS NULL
    OR oa_no IS NULL
    OR top_product_code IS NULL
    OR material_code IS NULL
    OR gap_material_code IS NULL
    OR gap_type IS NULL
    OR item_type IS NULL;

UPDATE lp_make_part_price_calc_row
   SET oa_no = COALESCE(oa_no, ''),
       parent_material_no = COALESCE(parent_material_no, ''),
       child_material_no = COALESCE(child_material_no, '')
 WHERE oa_no IS NULL
    OR parent_material_no IS NULL
    OR child_material_no IS NULL;

UPDATE lp_make_part_price_gap_item
   SET oa_no = COALESCE(oa_no, ''),
       parent_material_no = COALESCE(parent_material_no, ''),
       child_material_no = COALESCE(child_material_no, ''),
       missing_price_role = COALESCE(missing_price_role, ''),
       missing_material_no = COALESCE(missing_material_no, '')
 WHERE oa_no IS NULL
    OR parent_material_no IS NULL
    OR child_material_no IS NULL
    OR missing_price_role IS NULL
    OR missing_material_no IS NULL;

DELETE i1 FROM lp_price_prepare_item i1
JOIN lp_price_prepare_item i2
  ON i1.oa_no = i2.oa_no
 AND i1.top_product_code = i2.top_product_code
 AND i1.material_code = i2.material_code
 AND i1.id < i2.id;

DELETE g1 FROM lp_price_prepare_gap g1
JOIN lp_price_prepare_gap g2
  ON g1.oa_no = g2.oa_no
 AND g1.top_product_code = g2.top_product_code
 AND g1.material_code = g2.material_code
 AND g1.gap_material_code = g2.gap_material_code
 AND g1.gap_type = g2.gap_type
 AND g1.item_type = g2.item_type
 AND g1.id < g2.id;

DELETE p1 FROM lp_package_component_price p1
JOIN lp_package_component_price p2
  ON p1.package_material_code = p2.package_material_code
 AND p1.period_month = p2.period_month
 AND p1.source_top_product_code = p2.source_top_product_code
 AND p1.id < p2.id;

DELETE r1 FROM lp_make_part_price_calc_row r1
JOIN lp_make_part_price_calc_row r2
  ON r1.oa_no = r2.oa_no
 AND r1.parent_material_no = r2.parent_material_no
 AND r1.child_material_no = r2.child_material_no
 AND r1.id < r2.id;

DELETE mg1 FROM lp_make_part_price_gap_item mg1
JOIN lp_make_part_price_gap_item mg2
  ON mg1.oa_no = mg2.oa_no
 AND mg1.parent_material_no = mg2.parent_material_no
 AND mg1.child_material_no = mg2.child_material_no
 AND mg1.missing_price_role = mg2.missing_price_role
 AND mg1.missing_material_no = mg2.missing_material_no
 AND mg1.id < mg2.id;

ALTER TABLE lp_price_prepare_item
  MODIFY prepare_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '兼容字段：旧价格准备批次号，PPR-11 后主链路不依赖',
  MODIFY oa_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'OA单号',
  MODIFY top_product_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '顶级成品料号',
  MODIFY material_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '结算明细料号';

ALTER TABLE lp_price_prepare_gap
  MODIFY prepare_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '兼容字段：旧价格准备批次号，PPR-11 后主链路不依赖',
  MODIFY oa_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'OA单号',
  MODIFY top_product_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '顶级成品料号',
  MODIFY material_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '当前结算料号',
  MODIFY gap_material_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '真正缺数据的料号',
  MODIFY gap_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '缺口类型',
  MODIFY item_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '料号类型';

ALTER TABLE lp_make_part_price_calc_row
  MODIFY oa_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'OA单号；PPR-11 当前最终价唯一口径',
  MODIFY parent_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '制造件料号',
  MODIFY child_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原材料/毛坯料号';

ALTER TABLE lp_make_part_price_gap_item
  MODIFY oa_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'OA单号',
  MODIFY parent_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '制造件料号/原始料号',
  MODIFY child_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原材料/毛坯料号',
  MODIFY missing_price_role VARCHAR(16) NOT NULL DEFAULT '' COMMENT '缺价类型：RAW 原材料价 / SCRAP 废料价',
  MODIFY missing_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '真正要补价的料号';

CALL v110_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month');
CALL v110_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top');
CALL v110_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top');
CALL v110_drop_index_if_exists('lp_make_part_price_gap_item', 'uk_make_gap_batch_role');

CALL v110_add_index_if_not_exists(
  'lp_price_prepare_item',
  'uk_price_prepare_item_current',
  'UNIQUE KEY uk_price_prepare_item_current (oa_no, top_product_code, material_code)'
);

CALL v110_add_index_if_not_exists(
  'lp_price_prepare_gap',
  'uk_price_prepare_gap_current',
  'UNIQUE KEY uk_price_prepare_gap_current (oa_no, top_product_code, material_code, gap_material_code, gap_type, item_type)'
);

CALL v110_add_index_if_not_exists(
  'lp_package_component_price',
  'uk_pkg_price_month_top',
  'UNIQUE KEY uk_pkg_price_month_top (package_material_code, period_month, source_top_product_code)'
);

CALL v110_add_index_if_not_exists(
  'lp_make_part_price_calc_row',
  'uk_make_part_price_current',
  'UNIQUE KEY uk_make_part_price_current (oa_no, parent_material_no, child_material_no)'
);

CALL v110_add_index_if_not_exists(
  'lp_make_part_price_gap_item',
  'uk_make_gap_current',
  'UNIQUE KEY uk_make_gap_current (oa_no, parent_material_no, child_material_no, missing_price_role, missing_material_no)'
);

CALL v110_add_index_if_not_exists(
  'lp_package_component_price',
  'idx_pkg_price_month_top',
  'KEY idx_pkg_price_month_top (period_month, source_top_product_code)'
);

DROP PROCEDURE IF EXISTS v110_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v110_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v110_add_index_if_not_exists;
