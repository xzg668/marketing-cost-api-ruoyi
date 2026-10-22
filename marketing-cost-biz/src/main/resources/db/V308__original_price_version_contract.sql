-- V308 / PRC-01: 原稿动态取价契约。复用现有表，无表/业务数据删除。
-- 前置：现有因素、联动、区间和结果结构（含 V198 来源字段）。
-- 旧月价保留 LEGACY 范围、旧价格和旧 ID；财务发布使用独立方案及完整批次。
-- 本迁移不发布财务价格、不按业务单元猜组织、不改冻结历史或数量区间用途。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v308_column;
DROP PROCEDURE IF EXISTS v308_index;
DROP PROCEDURE IF EXISTS v308_drop_index;
DELIMITER //
CREATE PROCEDURE v308_column(IN t VARCHAR(64), IN c VARCHAR(64), IN definition TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name=t AND column_name=c) THEN
    SET @v308_ddl=CONCAT('ALTER TABLE `',t,'` ADD COLUMN `',c,'` ',definition);
    PREPARE v308_stmt FROM @v308_ddl;
    EXECUTE v308_stmt;
    DEALLOCATE PREPARE v308_stmt;
  END IF;
END//
CREATE PROCEDURE v308_index(IN t VARCHAR(64), IN n VARCHAR(64), IN definition TEXT)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics
                 WHERE table_schema=DATABASE() AND table_name=t AND index_name=n) THEN
    SET @v308_ddl=CONCAT('ALTER TABLE `',t,'` ADD ',definition);
    PREPARE v308_stmt FROM @v308_ddl;
    EXECUTE v308_stmt;
    DEALLOCATE PREPARE v308_stmt;
  END IF;
END//
CREATE PROCEDURE v308_drop_index(IN t VARCHAR(64), IN n VARCHAR(64))
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.statistics
              WHERE table_schema=DATABASE() AND table_name=t AND index_name=n) THEN
    SET @v308_ddl=CONCAT('ALTER TABLE `',t,'` DROP INDEX `',n,'`');
    PREPARE v308_stmt FROM @v308_ddl;
    EXECUTE v308_stmt;
    DEALLOCATE PREPARE v308_stmt;
  END IF;
END//
DELIMITER ;

CALL v308_column('lp_factor_monthly_price', 'price_org_code', 'VARCHAR(16) NOT NULL DEFAULT '''' COMMENT ''取价组织；历史未知为空，不按业务单元推测''');
CALL v308_column('lp_factor_monthly_price', 'scheme_code', 'VARCHAR(64) NOT NULL DEFAULT ''LEGACY'' COMMENT ''财务方案；LEGACY仅承接迁移前月价''');
CALL v308_column('lp_factor_monthly_price', 'condition_code', 'VARCHAR(24) NOT NULL DEFAULT ''GENERAL'' COMMENT ''GENERAL/NON_TENDER/TENDER''');
CALL v308_column('lp_factor_monthly_price', 'revision_no', 'INT NOT NULL DEFAULT 1 COMMENT ''财务发布批次修订号，发布后数值不可原位修改''');
CALL v308_column('lp_factor_monthly_price', 'unit', 'VARCHAR(32) NULL COMMENT ''有效价格单位；历史未知不回填''');
CALL v308_column('lp_factor_monthly_price', 'published_at', 'DATETIME(3) NULL');

CALL v308_column('lp_factor_upload_batch', 'price_org_code', 'VARCHAR(16) NOT NULL DEFAULT ''''');
CALL v308_column('lp_factor_upload_batch', 'publication_revision', 'INT NULL COMMENT ''同业务单元/组织/月的完整财务发布修订；旧导入为NULL''');
CALL v308_column('lp_factor_upload_batch', 'default_scheme_code', 'VARCHAR(64) NULL');
CALL v308_column('lp_factor_upload_batch', 'parser_version', 'VARCHAR(32) NULL');
CALL v308_column('lp_factor_upload_batch', 'published_at', 'DATETIME(3) NULL');

CALL v308_column('lp_factor_row_ref', 'source_column_number', 'INT NOT NULL DEFAULT 0 COMMENT ''1-based列号；历史未知为0''');
CALL v308_column('lp_factor_row_ref', 'source_cell_ref', 'VARCHAR(32) NULL');
CALL v308_column('lp_factor_row_ref', 'scheme_code', 'VARCHAR(64) NOT NULL DEFAULT ''LEGACY''');
CALL v308_column('lp_factor_row_ref', 'condition_code', 'VARCHAR(24) NOT NULL DEFAULT ''GENERAL''');
CALL v308_column('lp_factor_row_ref', 'source_value_text', 'TEXT NULL COMMENT ''原始数值文本，不能用格式化显示值代替''');
CALL v308_column('lp_factor_row_ref', 'source_formula_expr', 'TEXT NULL');
CALL v308_column('lp_factor_row_ref', 'source_number_format', 'VARCHAR(128) NULL');
CALL v308_column('lp_factor_row_ref', 'source_unit', 'VARCHAR(32) NULL');
CALL v308_column('lp_factor_row_ref', 'conversion_basis', 'VARCHAR(128) NULL COMMENT ''取方案值/本表均价/固定SUS换算等依据''');

CALL v308_column('lp_price_linked_item', 'rule_identity_key', 'CHAR(64) NULL COMMENT ''组织/料号/供应商/规格/单位的稳定摘要；旧规则不猜测回填''');
CALL v308_column('lp_price_linked_item', 'rule_revision', 'INT NULL');
CALL v308_column('lp_price_linked_item', 'rule_status', 'VARCHAR(24) NOT NULL DEFAULT ''LEGACY'' COMMENT ''LEGACY/DRAFT/PUBLISHED/REJECTED''');
CALL v308_column('lp_price_linked_item', 'expression_version', 'VARCHAR(32) NOT NULL DEFAULT ''LEGACY'' COMMENT ''可执行表达式和依赖快照契约版本''');

CALL v308_column('lp_price_range_factor_rule', 'pricing_month', 'CHAR(7) NULL COMMENT ''财务指定导入月，不从旧生效日期猜测''');
CALL v308_column('lp_price_range_factor_rule', 'org_code', 'VARCHAR(16) NULL');
CALL v308_column('lp_price_range_factor_rule', 'supplier_code', 'VARCHAR(64) NULL');
CALL v308_column('lp_price_range_factor_rule', 'supplier_name', 'VARCHAR(128) NULL');
CALL v308_column('lp_price_range_factor_rule', 'rule_identity_key', 'CHAR(64) NULL');
CALL v308_column('lp_price_range_factor_rule', 'rule_status', 'VARCHAR(24) NOT NULL DEFAULT ''LEGACY''');
CALL v308_column('lp_price_range_factor_rule', 'source_upload_batch_id', 'BIGINT NULL');
CALL v308_column('lp_price_range_factor_rule', 'source_input_snapshot_json', 'JSON NULL');
CALL v308_column('lp_price_range_factor_rule', 'extrapolation_json', 'JSON NULL COMMENT ''仅存已核实的外推起点/步长/档差/税价及证据，NULL不可外推''');

CALL v308_column('lp_price_range_item', 'lower_inclusive', 'TINYINT NOT NULL DEFAULT 1 COMMENT ''旧解析器为闭区间，保留既有QTY行为''');
CALL v308_column('lp_price_range_item', 'upper_inclusive', 'TINYINT NOT NULL DEFAULT 1');
CALL v308_column('lp_price_range_item', 'source_sheet_name', 'VARCHAR(128) NULL');
CALL v308_column('lp_price_range_item', 'source_cell_ref', 'VARCHAR(32) NULL');
CALL v308_column('lp_price_range_item', 'source_value_text', 'TEXT NULL');
CALL v308_column('lp_price_range_item', 'source_formula_expr', 'TEXT NULL');
CALL v308_column('lp_price_range_item', 'source_number_format', 'VARCHAR(128) NULL');

CALL v308_column('lp_price_linked_calc_item', 'input_snapshot_json', 'JSON NULL COMMENT ''完整规则/因素/客户输入/单位税价版本快照；旧结果不伪造''');
CALL v308_column('lp_price_linked_calc_item', 'calculator_version', 'VARCHAR(32) NULL');

-- 增加小数精度同时扩大整数位，不缩窄旧值；原始文本另存来源记录。
ALTER TABLE lp_factor_monthly_price MODIFY price DECIMAL(34,16) NULL;
ALTER TABLE lp_factor_row_ref MODIFY price DECIMAL(34,16) NULL, MODIFY original_price DECIMAL(34,16) NULL;
ALTER TABLE lp_price_range_item MODIFY range_low DECIMAL(34,16) NOT NULL,
  MODIFY range_high DECIMAL(34,16) NOT NULL,
  MODIFY price_excl_tax DECIMAL(34,16) NULL, MODIFY price_incl_tax DECIMAL(34,16) NULL;

-- 先建立新的唯一性约束，再替换旧约束；已有行的默认值仍保证原业务键唯一。
CALL v308_index('lp_factor_monthly_price', 'uk_factor_price_version',
  'UNIQUE KEY uk_factor_price_version (factor_identity_id,price_org_code,price_month,scheme_code,condition_code,revision_no)');
CALL v308_drop_index('lp_factor_monthly_price', 'uk_factor_monthly_price');
CALL v308_index('lp_factor_upload_batch', 'uk_factor_publication',
  'UNIQUE KEY uk_factor_publication (business_unit_type,price_org_code,price_month,publication_revision)');
CALL v308_index('lp_factor_upload_batch', 'idx_factor_publication_select',
  'KEY idx_factor_publication_select (business_unit_type,price_org_code,price_month,import_type,status,publication_revision)');
CALL v308_index('lp_factor_row_ref', 'uk_factor_source_cell',
  'UNIQUE KEY uk_factor_source_cell (factor_upload_batch_id,source_sheet_name,source_row_number,source_column_number,scheme_code,condition_code)');
CALL v308_drop_index('lp_factor_row_ref', 'uk_factor_row_ref');
CALL v308_index('lp_price_linked_item', 'uk_linked_rule_version',
  'UNIQUE KEY uk_linked_rule_version (business_unit_type,rule_identity_key,pricing_month,rule_revision)');
CALL v308_index('lp_price_range_factor_rule', 'uk_range_rule_version',
  'UNIQUE KEY uk_range_rule_version (business_unit_type,rule_identity_key,pricing_month,version_no)');

-- 仅归并已确认 Cu/1#Cu 的平均价身份。其他价源、Pcu 和不锈钢规格不动。
-- 旧绑定/月价行仍指向旧 ID；canonical 映射供新契约使用，不能直接改写历史引用。
-- 临时表保存本次旧 ID -> 主身份对应，不增设第二套身份主数据。
DROP TEMPORARY TABLE IF EXISTS v308_cu_master;
CREATE TEMPORARY TABLE v308_cu_master AS
SELECT business_unit_type, MIN(id) AS master_id
FROM lp_factor_identity
WHERE UPPER(REPLACE(TRIM(short_name),' ','')) IN ('CU','1#CU')
  AND UPPER(REPLACE(TRIM(price_source),' ','')) IN ('平均价','月平均价','月均价','AVERAGE','AVG')
GROUP BY business_unit_type;

DROP PROCEDURE IF EXISTS v308_map_cu;
DELIMITER //
CREATE PROCEDURE v308_map_cu()
BEGIN
  IF EXISTS (
    SELECT 1 FROM lp_factor_identity f JOIN v308_cu_master m USING (business_unit_type)
    WHERE UPPER(REPLACE(TRIM(f.short_name),' ','')) IN ('CU','1#CU')
      AND UPPER(REPLACE(TRIM(f.price_source),' ','')) IN ('平均价','月平均价','月均价','AVERAGE','AVG')
      AND ((f.canonical_factor_identity_id IS NOT NULL AND f.canonical_factor_identity_id <> m.master_id)
        OR (f.canonical_factor_key IS NOT NULL AND f.canonical_factor_key NOT IN ('AVG|CU','AVG|1#CU')))
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V308: conflicting Cu canonical mapping; inspect before migration';
  END IF;
  UPDATE lp_factor_identity f JOIN v308_cu_master m USING (business_unit_type)
  SET f.canonical_factor_key='AVG|1#CU', f.canonical_factor_identity_id=m.master_id,
      f.updated_at=f.updated_at
  WHERE UPPER(REPLACE(TRIM(f.short_name),' ','')) IN ('CU','1#CU')
    AND UPPER(REPLACE(TRIM(f.price_source),' ','')) IN ('平均价','月平均价','月均价','AVERAGE','AVG');
END//
DELIMITER ;
CALL v308_map_cu();
DROP PROCEDURE v308_map_cu;
DROP TEMPORARY TABLE v308_cu_master;
DROP PROCEDURE v308_drop_index;
DROP PROCEDURE v308_index;
DROP PROCEDURE v308_column;
