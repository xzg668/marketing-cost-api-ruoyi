-- T5: 固化报价 BOM 成本行使用的 U9 组织，供后续包装件/取价链路继续传递。
-- 历史行不做业务猜测回填；新拍平链路必须由上游产品行组织写入。

DROP PROCEDURE IF EXISTS v182_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v182_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v182_add_column_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_column_name VARCHAR(128),
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
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_name, ' ', p_column_definition);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

CREATE PROCEDURE v182_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
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
    SET @sql = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_definition);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

CALL v182_add_column_if_not_exists(
  'lp_bom_costing_row',
  'price_org_code',
  'VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER built_at'
);

CALL v182_add_column_if_not_exists(
  'lp_bom_costing_row',
  'material_organization_code',
  'VARCHAR(32) DEFAULT NULL COMMENT ''料品主档组织：COMMERCIAL=商用，PLATE=板换'' AFTER price_org_code'
);

CALL v182_add_index_if_not_exists(
  'lp_bom_costing_row',
  'idx_bom_costing_org_scope',
  'INDEX idx_bom_costing_org_scope (price_org_code, material_organization_code, oa_no, oa_form_item_id, top_product_code, period_month)'
);

DROP PROCEDURE IF EXISTS v182_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v182_add_index_if_not_exists;
