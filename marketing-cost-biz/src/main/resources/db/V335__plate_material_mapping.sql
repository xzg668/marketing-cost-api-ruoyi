-- PMM-01：型号配置与版本材质独立存储，不回填历史、不导入配置、不改变菜单。
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_plate_material_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(20) NOT NULL,
  normalized_model VARCHAR(255) NOT NULL,
  plate_material VARCHAR(32) NOT NULL,
  row_version INT NOT NULL DEFAULT 0,
  source_type VARCHAR(12) NOT NULL,
  source_file_name VARCHAR(255) NULL,
  source_file_sha256 CHAR(64) NULL,
  source_sheet_name VARCHAR(64) NULL,
  source_row_number INT NULL,
  import_batch_id VARCHAR(64) NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_by BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_plate_material_model (business_unit_type, normalized_model),
  CONSTRAINT ck_plate_material_unit CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD')),
  CONSTRAINT ck_plate_material_model CHECK (
    normalized_model <> '' AND normalized_model = TRIM(normalized_model)),
  CONSTRAINT ck_plate_material_value CHECK (
    plate_material IN ('水氟SUS304','水氟316L','氟氟SUS304','氟氟316L','水水SUS304','水水316L')),
  CONSTRAINT ck_plate_material_version CHECK (row_version >= 0),
  CONSTRAINT ck_plate_material_source CHECK (
    (source_type = 'MANUAL' AND source_file_name IS NULL AND source_file_sha256 IS NULL
      AND source_sheet_name IS NULL AND source_row_number IS NULL AND import_batch_id IS NULL)
    OR
    (source_type = 'EXCEL' AND source_file_name IS NOT NULL AND source_file_name <> ''
      AND source_file_sha256 IS NOT NULL AND CHAR_LENGTH(source_file_sha256) = 64
      AND source_sheet_name IS NOT NULL AND source_sheet_name = '提供财务'
      AND source_row_number IS NOT NULL AND source_row_number >= 3
      AND import_batch_id IS NOT NULL AND import_batch_id <> ''))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin
  COMMENT='型号—板片材质对照表；按业务单元共享，不按月份或取价组织分拆';

DROP PROCEDURE IF EXISTS v335_plate_material;
DELIMITER $$
CREATE PROCEDURE v335_plate_material()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='lp_quote_tech_data_version'
        AND column_name='plate_material_json') THEN
    ALTER TABLE lp_quote_tech_data_version ADD COLUMN plate_material_json JSON NULL
      COMMENT 'PROFILE版本采用的板片材质及来源；NULL表示历史未采集';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
      WHERE constraint_schema=DATABASE() AND table_name='lp_quote_tech_data_version'
        AND constraint_name='ck_quote_tech_plate_material_schema') THEN
    ALTER TABLE lp_quote_tech_data_version ADD CONSTRAINT ck_quote_tech_plate_material_schema
      CHECK (plate_material_json IS NULL OR content_schema_version=2);
  END IF;
END$$
DELIMITER ;
CALL v335_plate_material();
DROP PROCEDURE v335_plate_material;

-- 独立保护新字段，原有不可变字段和状态转换触发器保持原样。
DELIMITER $$
DROP TRIGGER IF EXISTS trg_quote_tech_plate_material_bu$$
CREATE TRIGGER trg_quote_tech_plate_material_bu BEFORE UPDATE ON lp_quote_tech_data_version
FOR EACH ROW
BEGIN
  IF OLD.version_status <> 'DRAFT'
      AND NOT (NEW.plate_material_json <=> OLD.plate_material_json) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='TECH_DATA_VERSION_IMMUTABLE: plate material cannot change';
  END IF;
END$$
DELIMITER ;
