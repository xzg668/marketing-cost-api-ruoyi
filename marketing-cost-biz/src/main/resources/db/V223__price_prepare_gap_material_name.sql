-- 价格缺口页按 gap_material_code 展示品名；此前缺口表只保存料号，导致页面品名整列为空。

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v223_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v223_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v223_add_column_if_not_exists(
  'lp_price_prepare_gap',
  'material_name',
  '`material_name` VARCHAR(255) DEFAULT NULL COMMENT ''缺口料号品名快照'' AFTER `gap_material_code`'
);

-- 先按同一报价产品、月份和缺口料号从当前 BOM 结算明细回填历史缺口。
UPDATE lp_price_prepare_gap g
JOIN lp_bom_costing_row b
  ON b.oa_no = g.oa_no
 AND (g.oa_form_item_id IS NULL OR b.oa_form_item_id = g.oa_form_item_id)
 AND (g.period_month IS NULL OR g.period_month = '' OR b.period_month = g.period_month)
 AND b.material_code = COALESCE(NULLIF(g.gap_material_code, ''), g.material_code)
SET g.material_name = b.material_name
WHERE (g.material_name IS NULL OR g.material_name = '')
  AND b.material_name IS NOT NULL
  AND b.material_name <> '';

-- BOM 中没有的历史缺口，再从既有价格准备明细按真实缺口料号回填。
UPDATE lp_price_prepare_gap g
JOIN lp_price_prepare_item i
  ON i.oa_no = g.oa_no
 AND (g.oa_form_item_id IS NULL OR i.oa_form_item_id = g.oa_form_item_id)
 AND (g.period_month IS NULL OR g.period_month = '' OR i.period_month = g.period_month)
 AND i.material_code = COALESCE(NULLIF(g.gap_material_code, ''), g.material_code)
SET g.material_name = i.material_name
WHERE (g.material_name IS NULL OR g.material_name = '')
  AND i.material_name IS NOT NULL
  AND i.material_name <> '';

DROP PROCEDURE IF EXISTS v223_add_column_if_not_exists;
