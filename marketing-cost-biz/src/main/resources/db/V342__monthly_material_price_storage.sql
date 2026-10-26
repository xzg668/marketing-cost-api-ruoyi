-- MPP-02: additive storage only. No imports, menu initialization or historical backfill.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_household_inquiry_import_batch (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  preview_id VARCHAR(64) NOT NULL,
  publish_request_id VARCHAR(64) NOT NULL,
  preview_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_key VARCHAR(512) NOT NULL,
  file_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  file_name VARCHAR(255) NOT NULL,
  worksheet_name VARCHAR(64) NOT NULL,
  parser_schema VARCHAR(32) NOT NULL,
  source_month CHAR(7) NOT NULL,
  source_month_origin VARCHAR(32) NOT NULL,
  pricing_unit VARCHAR(255) NOT NULL,
  currency CHAR(3) NOT NULL,
  tax_basis VARCHAR(16) NOT NULL,
  preview_json LONGTEXT NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PREVIEW',
  expires_at DATETIME(6) NOT NULL,
  result_json LONGTEXT NULL,
  published_at DATETIME(6) NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_household_preview (preview_id),
  UNIQUE KEY uk_household_publish_request (publish_request_id),
  KEY idx_household_batch_history (business_unit_type,created_at,id),
  CONSTRAINT ck_household_batch_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD')),
  CONSTRAINT ck_household_batch_month CHECK (source_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND LEFT(source_month,4)<>'0000'),
  CONSTRAINT ck_household_batch_context CHECK (pricing_unit<>'' AND currency REGEXP '^[A-Z]{3}$'
    AND tax_basis IN ('EXCLUDED','INCLUDED') AND source_month_origin IN ('FILE_CONTENT','OPERATOR_INPUT')),
  CONSTRAINT ck_household_batch_parser CHECK (worksheet_name='10月线圈询价' AND parser_schema='HOUSEHOLD_COIL_V1'),
  CONSTRAINT ck_household_batch_json CHECK (JSON_VALID(preview_json) AND JSON_TYPE(preview_json)='OBJECT'
    AND (result_json IS NULL OR (JSON_VALID(result_json) AND JSON_TYPE(result_json)='OBJECT'))),
  CONSTRAINT ck_household_batch_state CHECK (
    (status='PUBLISHED' AND result_json IS NOT NULL AND published_at IS NOT NULL)
    OR (status IN ('PREVIEW','CANCELLED','EXPIRED') AND result_json IS NULL AND published_at IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='家用询价预览、不可变原件引用及发布回执';

CREATE TABLE IF NOT EXISTS lp_household_inquiry_price (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  business_unit_type VARCHAR(32) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  material_name VARCHAR(255) NULL,
  specification VARCHAR(512) NULL,
  revision_no INT NOT NULL,
  previous_price_id BIGINT NULL,
  import_batch_id BIGINT NOT NULL,
  source_rows_json LONGTEXT NOT NULL,
  unit_price DECIMAL(24,12) NOT NULL,
  household_rate DECIMAL(18,12) NOT NULL,
  pricing_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  record_fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  reference_json LONGTEXT NOT NULL,
  current_flag TINYINT NOT NULL DEFAULT 1,
  current_material VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN current_flag=1 THEN material_code ELSE NULL END) STORED,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_household_revision (business_unit_type,material_code,revision_no),
  UNIQUE KEY uk_household_batch_material (import_batch_id,material_code),
  UNIQUE KEY uk_household_current (business_unit_type,current_material),
  KEY idx_household_import_batch (import_batch_id,id),
  KEY idx_household_previous (previous_price_id),
  CONSTRAINT fk_household_price_batch FOREIGN KEY (import_batch_id) REFERENCES lp_household_inquiry_import_batch(id),
  CONSTRAINT fk_household_price_previous FOREIGN KEY (previous_price_id) REFERENCES lp_household_inquiry_price(id),
  CONSTRAINT ck_household_price_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD') AND material_code<>''),
  CONSTRAINT ck_household_price_revision CHECK (revision_no>0 AND (previous_price_id IS NULL OR previous_price_id>0)),
  CONSTRAINT ck_household_price_values CHECK (unit_price>=0 AND household_rate>=0 AND current_flag IN (0,1)),
  CONSTRAINT ck_household_price_json CHECK (JSON_VALID(reference_json) AND JSON_TYPE(reference_json)='OBJECT'
    AND JSON_VALID(source_rows_json) AND JSON_TYPE(source_rows_json)='ARRAY' AND JSON_LENGTH(source_rows_json)>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='家用询价不可变来源版本；仅当前标记允许1转0';

CREATE TABLE IF NOT EXISTS lp_material_monthly_family (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  family_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  family_canonical LONGTEXT NOT NULL,
  family_json LONGTEXT NOT NULL,
  pricing_month CHAR(7) NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  material_code VARCHAR(64) NOT NULL,
  specification VARCHAR(512) NULL,
  pricing_unit VARCHAR(255) NOT NULL,
  currency CHAR(3) NOT NULL,
  tax_basis VARCHAR(16) NOT NULL,
  requested_supplier_code VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_monthly_family_hash (family_hash),
  KEY idx_monthly_family_query (business_unit_type,pricing_month,material_code,id),
  CONSTRAINT ck_monthly_family_hash CHECK (family_hash=SHA2(family_canonical,256)),
  CONSTRAINT ck_monthly_family_json CHECK (JSON_VALID(family_json) AND JSON_TYPE(family_json)='OBJECT'),
  CONSTRAINT ck_monthly_family_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD') AND material_code<>'' AND pricing_unit<>''),
  CONSTRAINT ck_monthly_family_month CHECK (pricing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND LEFT(pricing_month,4)<>'0000'),
  CONSTRAINT ck_monthly_family_context CHECK (currency REGEXP '^[A-Z]{3}$' AND tax_basis IN ('EXCLUDED','INCLUDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='月价稳定竞争空间与数据库锁，不是价格占位';

CREATE TABLE IF NOT EXISTS lp_material_monthly_price (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  family_id BIGINT NOT NULL,
  applicability_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  applicability_canonical LONGTEXT NOT NULL,
  applicability_json LONGTEXT NOT NULL,
  organization_mask TINYINT UNSIGNED NOT NULL COMMENT '完整范围组织并集：210=1、220=2；仅用于存储校验，不参与身份',
  pricing_type VARCHAR(32) NOT NULL,
  unit_price DECIMAL(38,18) NOT NULL,
  household_rate DECIMAL(18,12) NULL,
  source_evidence_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  -- Lossless transport envelope: document holds canonical JSON as text, never a DB numeric value.
  source_evidence_json LONGTEXT NOT NULL,
  household_source_id BIGINT NULL,
  first_use_json LONGTEXT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_monthly_price_applicability (family_id,applicability_hash),
  KEY idx_monthly_price_source (household_source_id,id),
  CONSTRAINT fk_monthly_price_family FOREIGN KEY (family_id) REFERENCES lp_material_monthly_family(id),
  CONSTRAINT fk_monthly_price_household FOREIGN KEY (household_source_id) REFERENCES lp_household_inquiry_price(id),
  CONSTRAINT ck_monthly_price_hash CHECK (applicability_hash=SHA2(applicability_canonical,256)),
  CONSTRAINT ck_monthly_price_organizations CHECK (organization_mask IN (1,2,3)),
  CONSTRAINT ck_monthly_price_values CHECK (unit_price>=0 AND (
    (pricing_type='NORMAL' AND household_rate IS NULL AND household_source_id IS NULL)
    OR (pricing_type='HOUSEHOLD_INQUIRY' AND household_rate IS NOT NULL AND household_rate>=0
      AND unit_price<1000000000000 AND unit_price=ROUND(unit_price,12) AND household_source_id IS NOT NULL))),
  CONSTRAINT ck_monthly_price_json CHECK (JSON_VALID(applicability_json) AND JSON_TYPE(applicability_json)='OBJECT'
    AND ((JSON_EXTRACT(applicability_json,'$.schemaVersion')=1 AND JSON_TYPE(JSON_EXTRACT(applicability_json,'$.document'))='STRING') IS TRUE)
    AND JSON_VALID(first_use_json) AND JSON_TYPE(first_use_json)='OBJECT'
    AND ((JSON_EXTRACT(first_use_json,'$.schemaVersion')=1 AND JSON_TYPE(JSON_EXTRACT(first_use_json,'$.document'))='STRING') IS TRUE)
    AND JSON_VALID(source_evidence_json) AND JSON_TYPE(source_evidence_json)='OBJECT'),
  CONSTRAINT ck_monthly_price_evidence CHECK ((
    JSON_EXTRACT(source_evidence_json,'$.schemaVersion')=1
    AND JSON_TYPE(JSON_EXTRACT(source_evidence_json,'$.document'))='STRING'
    AND source_evidence_hash=SHA2(JSON_UNQUOTE(JSON_EXTRACT(source_evidence_json,'$.document')),256)) IS TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='不可变月价与完整适用片段及独立来源依据';

CREATE TABLE IF NOT EXISTS lp_material_monthly_adoption (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  request_id VARCHAR(64) NOT NULL,
  input_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  oa_no VARCHAR(64) NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  product_code VARCHAR(64) NOT NULL,
  pricing_month CHAR(7) NOT NULL,
  consumer_org VARCHAR(3) NOT NULL,
  request_json LONGTEXT NOT NULL,
  result_json LONGTEXT NOT NULL,
  created_by VARCHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_monthly_adoption_request (request_id),
  KEY idx_monthly_adoption_product (business_unit_type,oa_no,oa_form_item_id,pricing_month,id),
  CONSTRAINT ck_monthly_adoption_scope CHECK (business_unit_type IN ('COMMERCIAL','HOUSEHOLD') AND consumer_org IN ('210','220')),
  CONSTRAINT ck_monthly_adoption_identity CHECK (request_id<>'' AND input_digest REGEXP '^[0-9a-f]{64}$' AND oa_form_item_id>0),
  CONSTRAINT ck_monthly_adoption_month CHECK (pricing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND LEFT(pricing_month,4)<>'0000'),
  CONSTRAINT ck_monthly_adoption_json CHECK (JSON_VALID(request_json) AND JSON_TYPE(request_json)='OBJECT'
    AND JSON_VALID(result_json) AND JSON_TYPE(result_json)='OBJECT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='独立采用回执，不代表准备或成品核算成功';

CREATE TABLE IF NOT EXISTS lp_material_monthly_price_ref (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  monthly_price_id BIGINT NOT NULL,
  ref_type VARCHAR(16) NOT NULL,
  adoption_id BIGINT NULL,
  prepare_item_id BIGINT NULL,
  cost_version_id BIGINT NULL,
  parent_price_id BIGINT NULL,
  target_id BIGINT GENERATED ALWAYS AS (COALESCE(adoption_id,prepare_item_id,cost_version_id,parent_price_id)) STORED,
  consumer_org VARCHAR(3) NOT NULL,
  use_path LONGTEXT NOT NULL,
  path_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin GENERATED ALWAYS AS (SHA2(use_path,256)) STORED,
  cumulative_quantity DECIMAL(40,16) NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_monthly_price_reference (monthly_price_id,ref_type,target_id,consumer_org,path_hash),
  KEY idx_monthly_reference_adoption (adoption_id,monthly_price_id),
  KEY idx_monthly_reference_prepare (prepare_item_id,monthly_price_id),
  KEY idx_monthly_reference_version (cost_version_id,monthly_price_id),
  KEY idx_monthly_reference_parent (parent_price_id,monthly_price_id),
  CONSTRAINT fk_monthly_reference_price FOREIGN KEY (monthly_price_id) REFERENCES lp_material_monthly_price(id),
  CONSTRAINT fk_monthly_reference_adoption FOREIGN KEY (adoption_id) REFERENCES lp_material_monthly_adoption(id),
  CONSTRAINT fk_monthly_reference_prepare FOREIGN KEY (prepare_item_id) REFERENCES lp_price_prepare_item(id),
  CONSTRAINT fk_monthly_reference_version FOREIGN KEY (cost_version_id) REFERENCES lp_quote_cost_run_version(id),
  CONSTRAINT fk_monthly_reference_parent FOREIGN KEY (parent_price_id) REFERENCES lp_material_monthly_price(id),
  CONSTRAINT ck_monthly_reference_target CHECK (
    (ref_type='ADOPTION' AND adoption_id IS NOT NULL AND prepare_item_id IS NULL AND cost_version_id IS NULL AND parent_price_id IS NULL)
    OR (ref_type='PREPARE' AND adoption_id IS NULL AND prepare_item_id IS NOT NULL AND cost_version_id IS NULL AND parent_price_id IS NULL)
    OR (ref_type='COST_VERSION' AND adoption_id IS NULL AND prepare_item_id IS NULL AND cost_version_id IS NOT NULL AND parent_price_id IS NULL)
    OR (ref_type='DEPENDENCY' AND adoption_id IS NULL AND prepare_item_id IS NULL AND cost_version_id IS NULL AND parent_price_id IS NOT NULL AND parent_price_id<>monthly_price_id)),
  CONSTRAINT ck_monthly_reference_use CHECK (
    (ref_type='DEPENDENCY' AND consumer_org='' AND use_path='' AND cumulative_quantity IS NULL)
    OR (ref_type<>'DEPENDENCY' AND consumer_org IN ('210','220') AND (
      (use_path='' AND cumulative_quantity IS NULL) OR (use_path<>'' AND cumulative_quantity IS NOT NULL AND cumulative_quantity>0))))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='有类型月价引用：采用、准备、成功成本及派生依赖';

DROP PROCEDURE IF EXISTS v342_monthly_storage_columns;
DELIMITER $$
CREATE PROCEDURE v342_monthly_storage_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_price_prepare_item' AND column_name='monthly_price_id') THEN
    ALTER TABLE lp_price_prepare_item ADD monthly_price_id BIGINT NULL, ADD monthly_pricing_type VARCHAR(32) NULL,
      ADD KEY idx_prepare_monthly_price (monthly_price_id),
      ADD CONSTRAINT fk_prepare_monthly_price FOREIGN KEY (monthly_price_id) REFERENCES lp_material_monthly_price(id),
      ADD CONSTRAINT ck_prepare_monthly_pair CHECK ((monthly_price_id IS NULL AND monthly_pricing_type IS NULL)
        OR (monthly_price_id IS NOT NULL AND monthly_pricing_type IS NOT NULL AND monthly_pricing_type IN ('NORMAL','HOUSEHOLD_INQUIRY')));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_cost_run_part_item' AND column_name='monthly_price_id') THEN
    ALTER TABLE lp_cost_run_part_item ADD monthly_price_id BIGINT NULL, ADD monthly_pricing_type VARCHAR(32) NULL,
      ADD KEY idx_cost_part_monthly_price (monthly_price_id),
      ADD CONSTRAINT fk_cost_part_monthly_price FOREIGN KEY (monthly_price_id) REFERENCES lp_material_monthly_price(id),
      ADD CONSTRAINT ck_cost_part_monthly_pair CHECK ((monthly_price_id IS NULL AND monthly_pricing_type IS NULL)
        OR (monthly_price_id IS NOT NULL AND monthly_pricing_type IS NOT NULL AND monthly_pricing_type IN ('NORMAL','HOUSEHOLD_INQUIRY')));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_quote_cost_run_version' AND column_name='fee_policy_version') THEN
    ALTER TABLE lp_quote_cost_run_version ADD fee_policy_version VARCHAR(64) NULL,
      ADD jianji_snapshot_version INT NULL, ADD jianji_snapshot_json LONGTEXT NULL,
      ADD CONSTRAINT ck_cost_jianji_snapshot CHECK ((jianji_snapshot_version IS NULL AND jianji_snapshot_json IS NULL)
        OR (jianji_snapshot_version IS NOT NULL AND jianji_snapshot_version=1 AND jianji_snapshot_json IS NOT NULL AND JSON_VALID(jianji_snapshot_json) AND JSON_TYPE(jianji_snapshot_json)='OBJECT'
          AND ((JSON_EXTRACT(jianji_snapshot_json,'$.schemaVersion')=1 AND JSON_TYPE(JSON_EXTRACT(jianji_snapshot_json,'$.document'))='STRING') IS TRUE)));
  END IF;
END$$
DELIMITER ;
CALL v342_monthly_storage_columns();
DROP PROCEDURE v342_monthly_storage_columns;

DELIMITER $$
DROP TRIGGER IF EXISTS trg_monthly_price_no_update$$
CREATE TRIGGER trg_monthly_price_no_update BEFORE UPDATE ON lp_material_monthly_price FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_MONTHLY_PRICE_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_monthly_price_no_delete$$
CREATE TRIGGER trg_monthly_price_no_delete BEFORE DELETE ON lp_material_monthly_price FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_MONTHLY_PRICE_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_monthly_adoption_no_update$$
CREATE TRIGGER trg_monthly_adoption_no_update BEFORE UPDATE ON lp_material_monthly_adoption FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_ADOPTION_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_monthly_adoption_no_delete$$
CREATE TRIGGER trg_monthly_adoption_no_delete BEFORE DELETE ON lp_material_monthly_adoption FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_ADOPTION_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_monthly_reference_no_update$$
CREATE TRIGGER trg_monthly_reference_no_update BEFORE UPDATE ON lp_material_monthly_price_ref FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_monthly_reference_no_delete$$
CREATE TRIGGER trg_monthly_reference_no_delete BEFORE DELETE ON lp_material_monthly_price_ref FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_household_price_no_delete$$
CREATE TRIGGER trg_household_price_no_delete BEFORE DELETE ON lp_household_inquiry_price FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_SOURCE_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_household_price_retire_only$$
CREATE TRIGGER trg_household_price_retire_only BEFORE UPDATE ON lp_household_inquiry_price FOR EACH ROW
BEGIN
  IF NOT (OLD.current_flag=1 AND NEW.current_flag=0 AND NEW.id=OLD.id
    AND NEW.business_unit_type=OLD.business_unit_type AND NEW.material_code=OLD.material_code
    AND (NEW.material_name <=> OLD.material_name) AND (NEW.specification <=> OLD.specification)
    AND NEW.revision_no=OLD.revision_no AND (NEW.previous_price_id <=> OLD.previous_price_id)
    AND NEW.import_batch_id=OLD.import_batch_id AND NEW.source_rows_json=OLD.source_rows_json
    AND NEW.unit_price=OLD.unit_price AND NEW.household_rate=OLD.household_rate
    AND NEW.pricing_fingerprint=OLD.pricing_fingerprint AND NEW.record_fingerprint=OLD.record_fingerprint
    AND NEW.reference_json=OLD.reference_json AND NEW.created_at=OLD.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_SOURCE_IMMUTABLE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_household_published_batch_immutable$$
CREATE TRIGGER trg_household_published_batch_immutable BEFORE UPDATE ON lp_household_inquiry_import_batch FOR EACH ROW
BEGIN
  IF OLD.status='PUBLISHED' THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_PUBLICATION_IMMUTABLE';
  END IF;
  IF OLD.status<>'PREVIEW' OR NEW.status NOT IN ('PREVIEW','PUBLISHED','CANCELLED','EXPIRED') OR NOT (
    NEW.id=OLD.id AND NEW.business_unit_type=OLD.business_unit_type AND NEW.preview_id=OLD.preview_id
    AND NEW.publish_request_id=OLD.publish_request_id AND NEW.preview_fingerprint=OLD.preview_fingerprint
    AND NEW.file_key=OLD.file_key AND NEW.file_sha256=OLD.file_sha256 AND NEW.file_name=OLD.file_name
    AND NEW.worksheet_name=OLD.worksheet_name AND NEW.parser_schema=OLD.parser_schema
    AND NEW.source_month=OLD.source_month AND NEW.source_month_origin=OLD.source_month_origin
    AND NEW.pricing_unit=OLD.pricing_unit AND NEW.currency=OLD.currency AND NEW.tax_basis=OLD.tax_basis
    AND NEW.preview_json=OLD.preview_json AND NEW.expires_at=OLD.expires_at
    AND NEW.created_by=OLD.created_by AND NEW.created_at=OLD.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_PREVIEW_IMMUTABLE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_household_batch_no_delete$$
CREATE TRIGGER trg_household_batch_no_delete BEFORE DELETE ON lp_household_inquiry_import_batch FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_PUBLICATION_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_cost_monthly_snapshot_immutable$$
CREATE TRIGGER trg_cost_monthly_snapshot_immutable BEFORE UPDATE ON lp_quote_cost_run_version FOR EACH ROW
BEGIN
  IF OLD.status NOT IN ('TRIAL','RUNNING') AND NOT (
    (NEW.fee_policy_version <=> OLD.fee_policy_version)
    AND (NEW.jianji_snapshot_version <=> OLD.jianji_snapshot_version)
    AND (NEW.jianji_snapshot_json <=> OLD.jianji_snapshot_json)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_COST_SNAPSHOT_IMMUTABLE';
  END IF;
  IF OLD.status NOT IN ('TRIAL','RUNNING') AND NEW.status IN ('TRIAL','RUNNING')
    AND (OLD.jianji_snapshot_version IS NOT NULL OR OLD.fee_policy_version IS NOT NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_COST_SNAPSHOT_IMMUTABLE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_monthly_family_no_update$$
CREATE TRIGGER trg_monthly_family_no_update BEFORE UPDATE ON lp_material_monthly_family FOR EACH ROW
BEGIN
  -- The lock upsert is a no-op; stable identity contents may never change.
  IF NOT (NEW.id=OLD.id AND NEW.family_hash=OLD.family_hash AND NEW.family_canonical=OLD.family_canonical
    AND NEW.family_json=OLD.family_json AND NEW.pricing_month=OLD.pricing_month
    AND NEW.business_unit_type=OLD.business_unit_type AND NEW.material_code=OLD.material_code
    AND (NEW.specification <=> OLD.specification) AND NEW.pricing_unit=OLD.pricing_unit
    AND NEW.currency=OLD.currency AND NEW.tax_basis=OLD.tax_basis
    AND (NEW.requested_supplier_code <=> OLD.requested_supplier_code) AND NEW.created_at=OLD.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_FAMILY_IMMUTABLE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_monthly_family_no_delete$$
CREATE TRIGGER trg_monthly_family_no_delete BEFORE DELETE ON lp_material_monthly_family FOR EACH ROW
SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_FAMILY_IMMUTABLE'$$
DROP TRIGGER IF EXISTS trg_household_price_scope$$
CREATE TRIGGER trg_household_price_scope BEFORE INSERT ON lp_household_inquiry_price FOR EACH ROW
BEGIN
  IF NEW.current_flag<>1 OR NOT EXISTS (SELECT 1 FROM lp_household_inquiry_import_batch b
    WHERE b.id=NEW.import_batch_id AND b.business_unit_type=NEW.business_unit_type AND b.status='PREVIEW') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_VERSION_SCOPE';
  END IF;
  IF (NEW.revision_no=1 AND NEW.previous_price_id IS NOT NULL)
    OR (NEW.revision_no>1 AND (NEW.previous_price_id IS NULL OR NOT EXISTS (
      SELECT 1 FROM lp_household_inquiry_price p WHERE p.id=NEW.previous_price_id
      AND p.business_unit_type=NEW.business_unit_type AND p.material_code=NEW.material_code
      AND p.revision_no=NEW.revision_no-1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_VERSION_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_monthly_household_scope$$
CREATE TRIGGER trg_monthly_household_scope BEFORE INSERT ON lp_material_monthly_price FOR EACH ROW
BEGIN
  IF NEW.household_source_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_household_inquiry_price h JOIN lp_material_monthly_family f ON f.id=NEW.family_id
    JOIN lp_household_inquiry_import_batch b ON b.id=h.import_batch_id
    WHERE h.id=NEW.household_source_id AND h.business_unit_type=f.business_unit_type AND h.material_code=f.material_code
      AND b.status='PUBLISHED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_HOUSEHOLD_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_monthly_reference_scope$$
CREATE TRIGGER trg_monthly_reference_scope BEFORE INSERT ON lp_material_monthly_price_ref FOR EACH ROW
BEGIN
  DECLARE owner_unit VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
  DECLARE owner_month CHAR(7) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
  DECLARE allowed_organizations TINYINT UNSIGNED;
  SELECT f.business_unit_type,f.pricing_month,p.organization_mask INTO owner_unit,owner_month,allowed_organizations
    FROM lp_material_monthly_price p JOIN lp_material_monthly_family f ON f.id=p.family_id WHERE p.id=NEW.monthly_price_id;
  IF owner_unit IS NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE'; END IF;
  IF NEW.ref_type<>'DEPENDENCY' AND NOT (
    (NEW.consumer_org='210' AND (allowed_organizations & 1)<>0)
    OR (NEW.consumer_org='220' AND (allowed_organizations & 2)<>0)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE';
  END IF;
  IF NEW.ref_type='ADOPTION' AND NOT EXISTS (SELECT 1 FROM lp_material_monthly_adoption a
    WHERE a.id=NEW.adoption_id AND a.business_unit_type=owner_unit AND a.pricing_month=owner_month
      AND a.consumer_org=NEW.consumer_org) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE';
  END IF;
  IF NEW.ref_type='PREPARE' AND NOT EXISTS (SELECT 1 FROM lp_price_prepare_item i
    WHERE i.id=NEW.prepare_item_id AND BINARY i.business_unit_type=BINARY owner_unit
      AND BINARY i.period_month=BINARY owner_month AND i.monthly_price_id=NEW.monthly_price_id
      AND BINARY i.price_org_code=BINARY NEW.consumer_org AND i.status='READY') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE';
  END IF;
  IF NEW.ref_type='COST_VERSION' AND NOT EXISTS (SELECT 1 FROM lp_quote_cost_run_version v
    WHERE v.id=NEW.cost_version_id AND BINARY v.business_unit_type=BINARY owner_unit
      AND BINARY v.pricing_month=BINARY owner_month AND v.status IN ('SUCCESS','CONFIRMED')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE';
  END IF;
  IF NEW.ref_type='DEPENDENCY' AND NOT EXISTS (SELECT 1 FROM lp_material_monthly_price p
    JOIN lp_material_monthly_family f ON f.id=p.family_id WHERE p.id=NEW.parent_price_id
      AND f.business_unit_type=owner_unit AND f.pricing_month=owner_month AND p.pricing_type='NORMAL') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_REFERENCE_SCOPE';
  END IF;
END$$
DELIMITER ;

-- Validate nullable legacy links without changing any original columns or triggers.
DELIMITER $$
DROP TRIGGER IF EXISTS trg_prepare_monthly_scope_insert$$
CREATE TRIGGER trg_prepare_monthly_scope_insert BEFORE INSERT ON lp_price_prepare_item FOR EACH ROW
BEGIN
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p JOIN lp_material_monthly_family f ON f.id=p.family_id
    WHERE p.id=NEW.monthly_price_id AND BINARY f.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY f.material_code=BINARY NEW.material_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND BINARY f.pricing_month=BINARY NEW.period_month
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_prepare_monthly_scope_update$$
CREATE TRIGGER trg_prepare_monthly_scope_update BEFORE UPDATE ON lp_price_prepare_item FOR EACH ROW
BEGIN
  IF OLD.monthly_price_id IS NOT NULL AND NOT ((NEW.monthly_price_id <=> OLD.monthly_price_id) AND (NEW.monthly_pricing_type <=> OLD.monthly_pricing_type)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_IMMUTABLE';
  END IF;
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p JOIN lp_material_monthly_family f ON f.id=p.family_id
    WHERE p.id=NEW.monthly_price_id AND BINARY f.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY f.material_code=BINARY NEW.material_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND BINARY f.pricing_month=BINARY NEW.period_month
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_cost_part_monthly_scope_insert$$
CREATE TRIGGER trg_cost_part_monthly_scope_insert BEFORE INSERT ON lp_cost_run_part_item FOR EACH ROW
BEGIN
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p JOIN lp_material_monthly_family f ON f.id=p.family_id
    WHERE p.id=NEW.monthly_price_id AND BINARY f.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY f.material_code=BINARY NEW.part_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND EXISTS (SELECT 1 FROM lp_quote_cost_run_version v WHERE v.id=NEW.cost_run_version_id
        AND BINARY v.cost_run_no=BINARY NEW.cost_run_no AND BINARY v.oa_no=BINARY NEW.oa_no
        AND (v.oa_form_item_id <=> NEW.oa_form_item_id) AND BINARY v.product_code=BINARY NEW.product_code
        AND BINARY v.business_unit_type=BINARY NEW.business_unit_type
        AND BINARY v.pricing_month=BINARY f.pricing_month AND v.status IN ('TRIAL','RUNNING'))
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_cost_part_monthly_scope_update$$
CREATE TRIGGER trg_cost_part_monthly_scope_update BEFORE UPDATE ON lp_cost_run_part_item FOR EACH ROW
BEGIN
  IF OLD.monthly_price_id IS NOT NULL AND NOT ((NEW.monthly_price_id <=> OLD.monthly_price_id) AND (NEW.monthly_pricing_type <=> OLD.monthly_pricing_type)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_IMMUTABLE';
  END IF;
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p JOIN lp_material_monthly_family f ON f.id=p.family_id
    WHERE p.id=NEW.monthly_price_id AND BINARY f.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY f.material_code=BINARY NEW.part_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND EXISTS (SELECT 1 FROM lp_quote_cost_run_version v WHERE v.id=NEW.cost_run_version_id
        AND BINARY v.cost_run_no=BINARY NEW.cost_run_no AND BINARY v.oa_no=BINARY NEW.oa_no
        AND (v.oa_form_item_id <=> NEW.oa_form_item_id) AND BINARY v.product_code=BINARY NEW.product_code
        AND BINARY v.business_unit_type=BINARY NEW.business_unit_type
        AND BINARY v.pricing_month=BINARY f.pricing_month AND v.status IN ('TRIAL','RUNNING'))
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DELIMITER ;
