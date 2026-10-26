-- User-authorized single-table monthly pricing revision. V342 remains immutable history.
-- Refuse consolidation if monthly facts/links exist; no business history is deleted or rewritten.
SET NAMES utf8mb4;
DELIMITER $$
DROP PROCEDURE IF EXISTS v344_require_unused_monthly_tables$$
CREATE PROCEDURE v344_require_unused_monthly_tables()
BEGIN
  IF EXISTS (SELECT 1 FROM lp_material_monthly_price LIMIT 1)
    OR EXISTS (SELECT 1 FROM lp_material_monthly_family LIMIT 1)
    OR EXISTS (SELECT 1 FROM lp_material_monthly_adoption LIMIT 1)
    OR EXISTS (SELECT 1 FROM lp_material_monthly_price_ref LIMIT 1)
    OR EXISTS (SELECT 1 FROM lp_price_prepare_item WHERE monthly_price_id IS NOT NULL LIMIT 1)
    OR EXISTS (SELECT 1 FROM lp_cost_run_part_item WHERE monthly_price_id IS NOT NULL LIMIT 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_SINGLE_TABLE_REQUIRES_UNUSED_MONTHLY_STORAGE';
  END IF;
END$$
CALL v344_require_unused_monthly_tables()$$
DROP PROCEDURE v344_require_unused_monthly_tables$$
DELIMITER ;

DROP TRIGGER trg_monthly_household_scope;
DROP TRIGGER trg_prepare_monthly_scope_insert;
DROP TRIGGER trg_prepare_monthly_scope_update;
DROP TRIGGER trg_cost_part_monthly_scope_insert;
DROP TRIGGER trg_cost_part_monthly_scope_update;
DROP TABLE lp_material_monthly_price_ref;
DROP TABLE lp_material_monthly_adoption;
ALTER TABLE lp_material_monthly_price
  DROP FOREIGN KEY fk_monthly_price_family,
  DROP INDEX uk_monthly_price_applicability,
  DROP CHECK ck_monthly_price_hash,
  DROP COLUMN family_id,
  DROP COLUMN applicability_canonical,
  ADD family_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  ADD pricing_month CHAR(7) NOT NULL,
  ADD business_unit_type VARCHAR(32) NOT NULL,
  ADD material_code VARCHAR(64) NOT NULL,
  ADD specification VARCHAR(512) NULL,
  ADD pricing_unit VARCHAR(255) NOT NULL,
  ADD currency CHAR(3) NOT NULL,
  ADD tax_basis VARCHAR(16) NOT NULL,
  ADD requested_supplier_code VARCHAR(64) NULL,
  ADD source_type VARCHAR(64) NOT NULL,
  ADD source_record_id BIGINT NULL,
  ADD supplier_code VARCHAR(64) NULL,
  ADD supplier_name VARCHAR(255) NULL,
  ADD UNIQUE KEY uk_monthly_price_identity (family_hash,applicability_hash),
  ADD KEY idx_monthly_price_query (business_unit_type,pricing_month,material_code,id),
  ADD CONSTRAINT ck_monthly_price_identity CHECK (
    business_unit_type IN ('COMMERCIAL','HOUSEHOLD') AND material_code<>'' AND pricing_unit<>''
    AND pricing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND LEFT(pricing_month,4)<>'0000'
    AND currency REGEXP '^[A-Z]{3}$' AND tax_basis IN ('EXCLUDED','INCLUDED') AND source_type<>'');
DROP TABLE lp_material_monthly_family;

DELIMITER $$
CREATE TRIGGER trg_monthly_household_scope BEFORE INSERT ON lp_material_monthly_price FOR EACH ROW
BEGIN
  IF NEW.household_source_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_household_inquiry_price h JOIN lp_household_inquiry_import_batch b ON b.id=h.import_batch_id
    WHERE h.id=NEW.household_source_id AND h.business_unit_type=NEW.business_unit_type
      AND h.material_code=NEW.material_code AND b.status='PUBLISHED') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_HOUSEHOLD_SCOPE';
  END IF;
END$$
DELIMITER ;

-- Validate nullable legacy links without changing any original columns or triggers.
DELIMITER $$
DROP TRIGGER IF EXISTS trg_prepare_monthly_scope_insert$$
CREATE TRIGGER trg_prepare_monthly_scope_insert BEFORE INSERT ON lp_price_prepare_item FOR EACH ROW
BEGIN
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p
    WHERE p.id=NEW.monthly_price_id AND BINARY p.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY p.material_code=BINARY NEW.material_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND BINARY p.pricing_month=BINARY NEW.period_month
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
    SELECT 1 FROM lp_material_monthly_price p
    WHERE p.id=NEW.monthly_price_id AND BINARY p.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY p.material_code=BINARY NEW.material_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND BINARY p.pricing_month=BINARY NEW.period_month
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DROP TRIGGER IF EXISTS trg_cost_part_monthly_scope_insert$$
CREATE TRIGGER trg_cost_part_monthly_scope_insert BEFORE INSERT ON lp_cost_run_part_item FOR EACH ROW
BEGIN
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p
    WHERE p.id=NEW.monthly_price_id AND BINARY p.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY p.material_code=BINARY NEW.part_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND EXISTS (SELECT 1 FROM lp_quote_cost_run_version v WHERE v.id=NEW.cost_run_version_id
        AND BINARY v.cost_run_no=BINARY NEW.cost_run_no AND BINARY v.oa_no=BINARY NEW.oa_no
        AND (v.oa_form_item_id <=> NEW.oa_form_item_id) AND BINARY v.product_code=BINARY NEW.product_code
        AND BINARY v.business_unit_type=BINARY NEW.business_unit_type
        AND BINARY v.pricing_month=BINARY p.pricing_month AND v.status IN ('TRIAL','RUNNING'))
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
    SELECT 1 FROM lp_material_monthly_price p
    WHERE p.id=NEW.monthly_price_id AND BINARY p.business_unit_type=BINARY NEW.business_unit_type
      AND BINARY p.material_code=BINARY NEW.part_code AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
      AND EXISTS (SELECT 1 FROM lp_quote_cost_run_version v WHERE v.id=NEW.cost_run_version_id
        AND BINARY v.cost_run_no=BINARY NEW.cost_run_no AND BINARY v.oa_no=BINARY NEW.oa_no
        AND (v.oa_form_item_id <=> NEW.oa_form_item_id) AND BINARY v.product_code=BINARY NEW.product_code
        AND BINARY v.business_unit_type=BINARY NEW.business_unit_type
        AND BINARY v.pricing_month=BINARY p.pricing_month AND v.status IN ('TRIAL','RUNNING'))
      AND ((NEW.price_org_code='210' AND (p.organization_mask & 1)<>0)
        OR (NEW.price_org_code='220' AND (p.organization_mask & 2)<>0))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_PRICE_LINK_SCOPE';
  END IF;
END$$
DELIMITER ;
