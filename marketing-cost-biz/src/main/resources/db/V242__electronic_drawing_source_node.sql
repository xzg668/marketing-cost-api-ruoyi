-- =============================================================================
-- V242: 电子图库 Excel 来源证据与原始节点
-- -----------------------------------------------------------------------------
-- 约束：
--   1. 只新增 lp_electronic_drawing_source_node 一张表。
--   2. 电子图库文件证据复用补录版本表，混合树来源复用补录明细表。
--   3. 不保存 Excel 二进制，不新增导入批次、匹配规则或独立流程表。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v242_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v242_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v242_drop_index_if_exists;

DELIMITER //

CREATE PROCEDURE v242_add_column_if_not_exists(
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
    SET @v242_add_column_sql =
      CONCAT('ALTER TABLE `', p_table_name, '` ADD COLUMN ', p_column_definition);
    PREPARE v242_add_column_stmt FROM @v242_add_column_sql;
    EXECUTE v242_add_column_stmt;
    DEALLOCATE PREPARE v242_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v242_add_index_if_not_exists(
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
    SET @v242_add_index_sql =
      CONCAT('ALTER TABLE `', p_table_name, '` ', p_index_definition);
    PREPARE v242_add_index_stmt FROM @v242_add_index_sql;
    EXECUTE v242_add_index_stmt;
    DEALLOCATE PREPARE v242_add_index_stmt;
  END IF;
END //

CREATE PROCEDURE v242_drop_index_if_exists(
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
    SET @v242_drop_index_sql =
      CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE v242_drop_index_stmt FROM @v242_drop_index_sql;
    EXECUTE v242_drop_index_stmt;
    DEALLOCATE PREPARE v242_drop_index_stmt;
  END IF;
END //

DELIMITER ;

CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'electronic_drawing_no',
  '`electronic_drawing_no` VARCHAR(128) DEFAULT NULL COMMENT ''本次接口查询并冻结的产品图号'' AFTER `bom_source`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_file_name',
  '`source_file_name` VARCHAR(255) DEFAULT NULL COMMENT ''电子图库返回的 Excel 文件名'' AFTER `electronic_drawing_no`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_file_sha256',
  '`source_file_sha256` CHAR(64) DEFAULT NULL COMMENT ''电子图库 Excel SHA-256'' AFTER `source_file_name`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_file_size',
  '`source_file_size` BIGINT DEFAULT NULL COMMENT ''电子图库 Excel 字节数'' AFTER `source_file_sha256`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_sheet_name',
  '`source_sheet_name` VARCHAR(128) DEFAULT NULL COMMENT ''解析使用的工作表名称'' AFTER `source_file_size`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_acquired_at',
  '`source_acquired_at` DATETIME DEFAULT NULL COMMENT ''按上海时间记录的接口取数时间'' AFTER `source_sheet_name`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'source_request_id',
  '`source_request_id` VARCHAR(128) DEFAULT NULL COMMENT ''电子图库接口请求标识'' AFTER `source_acquired_at`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'material_org_code',
  '`material_org_code` VARCHAR(64) DEFAULT NULL COMMENT ''料号匹配与 U9 展开的物料组织'' AFTER `source_request_id`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_version',
  'composition_fingerprint',
  '`composition_fingerprint` CHAR(64) DEFAULT NULL COMMENT ''活动混合 BOM 内容指纹'' AFTER `material_org_code`');

CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_detail',
  'node_source_type',
  '`node_source_type` VARCHAR(32) DEFAULT NULL COMMENT ''节点来源：E_DRAWING/U9_EXPANDED'' AFTER `source_u9_bom_id`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_detail',
  'source_electronic_node_id',
  '`source_electronic_node_id` BIGINT DEFAULT NULL COMMENT ''电子图库原始挂接节点ID'' AFTER `node_source_type`');
CALL v242_add_column_if_not_exists(
  'lp_quote_bom_supplement_detail',
  'mapping_status',
  '`mapping_status` VARCHAR(32) DEFAULT NULL COMMENT ''AUTO_MATCHED/MANUALLY_SELECTED/U9_EXPANDED'' AFTER `source_electronic_node_id`');

-- V236 删除 task_id 后，历史唯一键会退化为全局(scope, version_no)。
-- 改为以准备记录为边界，允许不同报价产品各自维护版本号。
CALL v242_drop_index_if_exists(
  'lp_quote_bom_supplement_version',
  'uk_qbp_supp_version_task_scope');
CALL v242_add_index_if_not_exists(
  'lp_quote_bom_supplement_version',
  'uk_qbp_supp_version_prepare_scope',
  'ADD UNIQUE INDEX `uk_qbp_supp_version_prepare_scope` (`preparation_id`, `supplement_scope`, `version_no`)');
CALL v242_add_index_if_not_exists(
  'lp_quote_bom_supplement_version',
  'idx_qbp_supp_version_ed_source',
  'ADD INDEX `idx_qbp_supp_version_ed_source` (`preparation_id`, `electronic_drawing_no`, `material_org_code`, `source_file_sha256`)');
CALL v242_add_index_if_not_exists(
  'lp_quote_bom_supplement_detail',
  'idx_qbp_supp_detail_ed_source',
  'ADD INDEX `idx_qbp_supp_detail_ed_source` (`source_electronic_node_id`, `node_source_type`)');

CREATE TABLE IF NOT EXISTS lp_electronic_drawing_source_node (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  supplement_version_id BIGINT NOT NULL COMMENT 'lp_quote_bom_supplement_version.id',
  source_row_no INT NOT NULL COMMENT 'Excel 原始行号（从1开始）',
  source_sequence VARCHAR(128) NOT NULL COMMENT '电子图库工程序号',
  parent_source_sequence VARCHAR(128) DEFAULT NULL COMMENT '直接父工程序号',
  drawing_code VARCHAR(128) NOT NULL COMMENT '电子图库代号/图号',
  source_name VARCHAR(180) NOT NULL COMMENT '电子图库名称',
  qty DECIMAL(20,8) NOT NULL COMMENT '电子图库原始数量',
  material VARCHAR(255) DEFAULT NULL COMMENT '电子图库材料原文',
  importance_class VARCHAR(64) DEFAULT NULL COMMENT '物料重要性分类原文',
  hsf_risk_class VARCHAR(64) DEFAULT NULL COMMENT 'HSF 风险分类原文',
  reference_weight DECIMAL(20,8) DEFAULT NULL COMMENT '电子图库单重/重量原值',
  source_remark VARCHAR(1000) DEFAULT NULL COMMENT '电子图库备注原文',
  match_status VARCHAR(32) NOT NULL DEFAULT 'UNMATCHED'
    COMMENT 'UNMATCHED/AMBIGUOUS/AUTO_MATCHED/MANUALLY_SELECTED',
  resolved_material_code VARCHAR(64) DEFAULT NULL COMMENT '当前物料组织中解析出的正式 U9 料号',
  resolved_by VARCHAR(128) DEFAULT NULL COMMENT '解析主体：SYSTEM 或实际用户标识',
  resolved_at DATETIME DEFAULT NULL COMMENT '按上海时间记录的料号解析时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_ed_source_version_sequence (supplement_version_id, source_sequence),
  KEY idx_ed_source_version_status (supplement_version_id, match_status),
  CONSTRAINT ck_ed_source_qty_positive CHECK (qty > 0),
  CONSTRAINT ck_ed_source_resolution_complete CHECK (
    (resolved_material_code IS NULL AND resolved_by IS NULL AND resolved_at IS NULL)
    OR
    (resolved_material_code IS NOT NULL AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='电子图库 Excel 原始工程节点及本版本料号解析结果';

DROP PROCEDURE IF EXISTS v242_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v242_add_index_if_not_exists;
DROP PROCEDURE IF EXISTS v242_drop_index_if_exists;

-- V242 结束
