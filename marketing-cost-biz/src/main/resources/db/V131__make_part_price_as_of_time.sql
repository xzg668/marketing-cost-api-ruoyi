-- =============================================================================
-- V131: 自制件价格生成结果固化取价时点
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 月度调价批次会固化 price_as_of_time；自制件作为派生价格，也必须把同一时点
--      写入生成结果，避免任务重试或后续价格维护覆盖本批次口径。
--   2. 旧数据无法还原真实发起时点，按 created_at 回填；仍保持原有当前结果可读。
--   3. 原唯一键只按 OA + 父件 + 子件，会导致同一月份不同取价时点互相覆盖；
--      这里改为 OA + 期间 + 取价时点 + 父件 + 子件 + 废料，保留不同快照。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v131_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v131_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v131_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v131_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v131_drop_index_if_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64)
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

CREATE PROCEDURE v131_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

CALL v131_add_column_if_not_exists(
  'lp_make_part_price_calc_row',
  'price_as_of_time',
  'DATETIME NULL COMMENT ''取价时点；月度调价使用批次 price_as_of_time，普通生成默认期间月末'' AFTER `pricing_month`'
);

UPDATE lp_make_part_price_calc_row
   SET price_as_of_time = COALESCE(price_as_of_time, created_at, NOW())
 WHERE price_as_of_time IS NULL;

ALTER TABLE lp_make_part_price_calc_row
  MODIFY COLUMN price_as_of_time DATETIME NOT NULL COMMENT '取价时点；月度调价使用批次 price_as_of_time，普通生成默认期间月末';

UPDATE lp_make_part_price_calc_row
   SET pricing_month = COALESCE(pricing_month, ''),
       oa_no = COALESCE(oa_no, ''),
       parent_material_no = COALESCE(parent_material_no, ''),
       child_material_no = COALESCE(child_material_no, ''),
       scrap_code = COALESCE(scrap_code, '')
 WHERE pricing_month IS NULL
    OR oa_no IS NULL
    OR parent_material_no IS NULL
    OR child_material_no IS NULL
    OR scrap_code IS NULL;

ALTER TABLE lp_make_part_price_calc_row
  MODIFY pricing_month VARCHAR(7) NOT NULL DEFAULT '' COMMENT '价格月份 yyyy-MM',
  MODIFY oa_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'OA单号',
  MODIFY parent_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '制造件料号',
  MODIFY child_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原材料/毛坯料号',
  MODIFY scrap_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '回收废料料号';

CALL v131_drop_index_if_exists('lp_make_part_price_calc_row', 'uk_make_part_price_current');

DELETE r1 FROM lp_make_part_price_calc_row r1
JOIN lp_make_part_price_calc_row r2
  ON r1.oa_no = r2.oa_no
 AND r1.pricing_month = r2.pricing_month
 AND r1.price_as_of_time = r2.price_as_of_time
 AND r1.parent_material_no = r2.parent_material_no
 AND r1.child_material_no = r2.child_material_no
 AND r1.scrap_code = r2.scrap_code
 AND r1.id < r2.id;

CALL v131_add_index_if_not_exists(
  'lp_make_part_price_calc_row',
  'uk_make_part_price_current_as_of',
  'UNIQUE KEY uk_make_part_price_current_as_of (oa_no, pricing_month, price_as_of_time, parent_material_no, child_material_no, scrap_code)'
);

CALL v131_add_index_if_not_exists(
  'lp_make_part_price_calc_row',
  'idx_make_part_price_as_of_lookup',
  'KEY idx_make_part_price_as_of_lookup (parent_material_no, oa_no, business_unit_type, pricing_month, price_as_of_time)'
);

DROP PROCEDURE IF EXISTS v131_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v131_drop_index_if_exists;
DROP PROCEDURE IF EXISTS v131_add_index_if_not_exists;
