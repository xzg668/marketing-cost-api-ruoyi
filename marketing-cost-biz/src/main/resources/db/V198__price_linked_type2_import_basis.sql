-- =====================================================================
-- V198: 联动价类型 2 导入基础字段
--
-- 仅扩展结构，不回填历史数据，不修改旧公式、税口径、变量绑定或月度价格。
-- canonical_factor_key 暂不增加唯一约束，兼容历史同义影响因素并存。
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v198_add_column_if_not_exists;
DELIMITER //
CREATE PROCEDURE v198_add_column_if_not_exists(
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
    SET @v198_column_ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ',
      p_column_definition
    );
    PREPARE v198_column_stmt FROM @v198_column_ddl;
    EXECUTE v198_column_stmt;
    DEALLOCATE PREPARE v198_column_stmt;
  END IF;
END//
DELIMITER ;

DROP PROCEDURE IF EXISTS v198_add_index_if_not_exists;
DELIMITER //
CREATE PROCEDURE v198_add_index_if_not_exists(
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
    SET @v198_index_ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD ', p_index_definition
    );
    PREPARE v198_index_stmt FROM @v198_index_ddl;
    EXECUTE v198_index_stmt;
    DEALLOCATE PREPARE v198_index_stmt;
  END IF;
END//
DELIMITER ;

CALL v198_add_column_if_not_exists(
  'lp_factor_identity',
  'canonical_factor_key',
  'VARCHAR(128) NULL COMMENT ''统一因素键，如 AVG|1#CU'''
);
CALL v198_add_column_if_not_exists(
  'lp_factor_identity',
  'canonical_factor_identity_id',
  'BIGINT NULL COMMENT ''统一主影响因素身份 ID；主身份可指向自身'''
);
CALL v198_add_column_if_not_exists(
  'lp_factor_identity',
  'identity_origin',
  'VARCHAR(32) NULL COMMENT ''身份来源：STANDARD_IMPORT/TYPE2_AUTO_CREATE'''
);

CALL v198_add_index_if_not_exists(
  'lp_factor_identity',
  'idx_factor_identity_canonical_key',
  'KEY `idx_factor_identity_canonical_key` (`business_unit_type`, `canonical_factor_key`)'
);
CALL v198_add_index_if_not_exists(
  'lp_factor_identity',
  'idx_factor_identity_canonical_master',
  'KEY `idx_factor_identity_canonical_master` (`canonical_factor_identity_id`)'
);

CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_upload_batch_id',
  'BIGINT NULL COMMENT ''类型 2 来源上传批次'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_sheet_name',
  'VARCHAR(128) NULL COMMENT ''业务计算 Sheet 名称'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_row_number',
  'INT NULL COMMENT ''业务计算 Sheet 的 1-based 行号'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_formula_cell_ref',
  'VARCHAR(32) NULL COMMENT ''现含税价公式单元格，如 R5'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_formula_expr',
  'TEXT NULL COMMENT ''Excel 原始公式，不做变量替换'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_input_snapshot_json',
  'JSON NULL COMMENT ''原始输入字段、单元格、值、单位和因素身份快照'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_tax_included_price',
  'DECIMAL(20,8) NULL COMMENT ''Excel 导入当时的现含税价'''
);
CALL v198_add_column_if_not_exists(
  'lp_price_linked_item',
  'source_tax_excluded_price',
  'DECIMAL(20,8) NULL COMMENT ''Excel 导入当时的现不含税价'''
);

CALL v198_add_index_if_not_exists(
  'lp_price_linked_item',
  'idx_price_linked_item_source_trace',
  'KEY `idx_price_linked_item_source_trace` '
    '(`source_upload_batch_id`, `source_sheet_name`, `source_row_number`)'
);

DROP PROCEDURE IF EXISTS v198_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v198_add_column_if_not_exists;
