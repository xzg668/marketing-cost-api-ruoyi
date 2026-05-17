-- =============================================================================
-- V69: CMS 原材料对应回收废料映射
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 扩展 lp_material_scrap_ref 为 CMS 原材料到回收废料的当前有效映射表。
--   2. 只保留业务查询和自制件取价需要的当前映射字段。
--
-- 口径：
--   - material_code + scrap_code + business_unit_type 是当前映射唯一口径。
--   - 同一个 material_code 可以对应多个不同 scrap_code。
--   - CMS posting_period / effective_date 只追溯，不参与自制件取价匹配。
-- =============================================================================

DROP PROCEDURE IF EXISTS _cms_material_scrap_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_material_scrap_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE _cms_material_scrap_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @cms_material_scrap_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_column_definition);
    PREPARE cms_material_scrap_add_column_stmt FROM @cms_material_scrap_add_column_sql;
    EXECUTE cms_material_scrap_add_column_stmt;
    DEALLOCATE PREPARE cms_material_scrap_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _cms_material_scrap_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @cms_material_scrap_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE cms_material_scrap_add_index_stmt FROM @cms_material_scrap_add_index_sql;
    EXECUTE cms_material_scrap_add_index_stmt;
    DEALLOCATE PREPARE cms_material_scrap_add_index_stmt;
  END IF;
END //

DELIMITER ;

-- 空库单独执行 V69 时也要有 current 映射表；已有库会沿用 V25 创建的表。
CREATE TABLE IF NOT EXISTS lp_material_scrap_ref (
  id BIGINT NOT NULL AUTO_INCREMENT,
  material_code VARCHAR(64) NOT NULL COMMENT '部品或原材料代码',
  scrap_code VARCHAR(64) NOT NULL COMMENT '对应废料代码（CMS 体系）',
  ratio DECIMAL(10,6) DEFAULT 1.0 COMMENT '抵减比例（如铜沫 0.92）',
  effective_from DATE DEFAULT NULL COMMENT '生效日期',
  effective_to DATE DEFAULT NULL COMMENT '失效日期',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元隔离',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_material_scrap (business_unit_type, material_code, scrap_code),
  KEY idx_scrap_material (material_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部品-废料映射（联动价 scrap_price_incl 派生用）';

ALTER TABLE lp_material_scrap_ref
  MODIFY business_unit_type VARCHAR(32) NOT NULL DEFAULT 'COMMERCIAL' COMMENT '业务单元隔离';

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'material_name',
  'ADD COLUMN material_name VARCHAR(180) DEFAULT NULL COMMENT ''原材料名称'' AFTER material_code'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'material_spec',
  'ADD COLUMN material_spec VARCHAR(255) DEFAULT NULL COMMENT ''原材料规格'' AFTER material_name'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'material_unit',
  'ADD COLUMN material_unit VARCHAR(32) DEFAULT NULL COMMENT ''原材料单位'' AFTER material_spec'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'scrap_name',
  'ADD COLUMN scrap_name VARCHAR(180) DEFAULT NULL COMMENT ''回收废料名称'' AFTER scrap_code'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'scrap_spec',
  'ADD COLUMN scrap_spec VARCHAR(255) DEFAULT NULL COMMENT ''回收废料规格'' AFTER scrap_name'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'scrap_unit',
  'ADD COLUMN scrap_unit VARCHAR(32) DEFAULT NULL COMMENT ''回收废料单位'' AFTER scrap_spec'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'source_type',
  'ADD COLUMN source_type VARCHAR(32) DEFAULT NULL COMMENT ''来源类型：CMS_EXCEL/CMS_API/MANUAL'' AFTER business_unit_type'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'source_doc_no',
  'ADD COLUMN source_doc_no VARCHAR(128) DEFAULT NULL COMMENT ''CMS单据号'' AFTER source_type'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'cms_record_id',
  'ADD COLUMN cms_record_id VARCHAR(128) DEFAULT NULL COMMENT ''CMS主记录ID'' AFTER source_doc_no'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'link_detail_id',
  'ADD COLUMN link_detail_id VARCHAR(128) DEFAULT NULL COMMENT ''CMS关联明细ID'' AFTER cms_record_id'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'cms_posting_period',
  'ADD COLUMN cms_posting_period VARCHAR(7) DEFAULT NULL COMMENT ''CMS期间，仅追溯，MVP不参与匹配'' AFTER link_detail_id'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'cms_effective_date',
  'ADD COLUMN cms_effective_date DATE DEFAULT NULL COMMENT ''CMS生效时间，仅追溯，MVP不参与匹配'' AFTER cms_posting_period'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'approval_time',
  'ADD COLUMN approval_time DATE DEFAULT NULL COMMENT ''CMS审核时间'' AFTER cms_effective_date'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'sync_time',
  'ADD COLUMN sync_time DATE DEFAULT NULL COMMENT ''CMS同步时间'' AFTER approval_time'
);

CALL _cms_material_scrap_add_column_if_not_exists(
  'lp_material_scrap_ref',
  'remark',
  'ADD COLUMN remark VARCHAR(512) DEFAULT NULL COMMENT ''冲突、导入异常或人工备注'' AFTER sync_time'
);

CALL _cms_material_scrap_add_index_if_not_exists(
  'lp_material_scrap_ref',
  'idx_material_scrap_ref_bu_material',
  'ADD KEY idx_material_scrap_ref_bu_material (business_unit_type, material_code)'
);

DROP PROCEDURE IF EXISTS _cms_material_scrap_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_material_scrap_add_index_if_not_exists;
