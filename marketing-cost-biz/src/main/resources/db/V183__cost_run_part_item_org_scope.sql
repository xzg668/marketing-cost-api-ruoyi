-- T5: 部品取价结果继续携带 BOM 成本行的 U9 组织，供包装聚合/导出等下游链路使用。
-- 历史行不做业务猜测回填；新取价链路必须由 lp_bom_costing_row 透传组织。

DROP PROCEDURE IF EXISTS v183_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v183_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v183_add_column_if_not_exists(
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

CREATE PROCEDURE v183_add_index_if_not_exists(
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

CALL v183_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'price_org_code',
  'VARCHAR(32) DEFAULT NULL COMMENT ''U9报价组织：210=商用，220=板换'' AFTER remark'
);

CALL v183_add_column_if_not_exists(
  'lp_cost_run_part_item',
  'material_organization_code',
  'VARCHAR(32) DEFAULT NULL COMMENT ''料品主档组织：COMMERCIAL=商用，PLATE=板换'' AFTER price_org_code'
);

CALL v183_add_index_if_not_exists(
  'lp_cost_run_part_item',
  'idx_cost_run_part_org_scope',
  'INDEX idx_cost_run_part_org_scope (price_org_code, material_organization_code, oa_no, product_code)'
);

DROP PROCEDURE IF EXISTS v183_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v183_add_index_if_not_exists;
