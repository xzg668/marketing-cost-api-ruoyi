DELIMITER //
CREATE PROCEDURE create_index_if_not_exists_v77(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = p_table_name
      AND index_name = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE ', p_table_name, ' ADD ', p_index_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CREATE TABLE IF NOT EXISTS lp_price_linked_formula_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  linked_item_id BIGINT NOT NULL,
  material_code VARCHAR(64) DEFAULT NULL,
  old_formula_expr TEXT DEFAULT NULL,
  new_formula_expr TEXT DEFAULT NULL,
  old_formula_expr_cn TEXT DEFAULT NULL,
  new_formula_expr_cn TEXT DEFAULT NULL,
  change_source VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  changed_by VARCHAR(64) DEFAULT NULL,
  remark VARCHAR(512) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_formula_change_item (linked_item_id),
  KEY idx_formula_change_material (material_code),
  KEY idx_formula_change_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='联动价公式修改日志';

CREATE TABLE IF NOT EXISTS lp_price_variable_binding_change_log (
  id BIGINT NOT NULL AUTO_INCREMENT,
  binding_id BIGINT DEFAULT NULL,
  linked_item_id BIGINT NOT NULL,
  token_name VARCHAR(32) DEFAULT NULL,
  action VARCHAR(32) NOT NULL COMMENT 'INSERT/UPDATE/VERSION_SWITCH/DELETE/AUTO_OVERWRITE',
  old_source VARCHAR(32) DEFAULT NULL,
  new_source VARCHAR(32) DEFAULT NULL,
  old_factor_code VARCHAR(64) DEFAULT NULL,
  new_factor_code VARCHAR(64) DEFAULT NULL,
  old_factor_identity_id BIGINT DEFAULT NULL,
  new_factor_identity_id BIGINT DEFAULT NULL,
  old_factor_monthly_price_id BIGINT DEFAULT NULL,
  new_factor_monthly_price_id BIGINT DEFAULT NULL,
  old_price_source VARCHAR(64) DEFAULT NULL,
  new_price_source VARCHAR(64) DEFAULT NULL,
  old_excel_formula TEXT DEFAULT NULL,
  new_excel_formula TEXT DEFAULT NULL,
  change_source VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  changed_by VARCHAR(64) DEFAULT NULL,
  message VARCHAR(1024) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_binding_change_binding (binding_id),
  KEY idx_binding_change_item (linked_item_id),
  KEY idx_binding_change_created (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='联动价行级绑定变更日志';

CALL create_index_if_not_exists_v77('lp_excel_auto_binding_import_log', 'idx_auto_binding_log_created',
  'KEY idx_auto_binding_log_created (created_at)');

DROP PROCEDURE IF EXISTS create_index_if_not_exists_v77;
