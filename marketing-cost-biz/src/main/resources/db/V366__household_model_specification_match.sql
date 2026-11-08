-- Missing source codes are stored as NULL, never fabricated material codes.
-- Existing source rows and all saved financial evidence remain unchanged.
SET NAMES utf8mb4;
ALTER TABLE lp_household_inquiry_price
  MODIFY material_code VARCHAR(64) NULL,
  ADD match_key VARCHAR(66) GENERATED ALWAYS AS
    (CASE WHEN material_code IS NULL THEN CONCAT('S:',SHA2(specification,256)) ELSE CONCAT('C:',material_code) END) STORED,
  ADD current_match VARCHAR(66) GENERATED ALWAYS AS (CASE WHEN current_flag=1 THEN match_key ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_household_match_revision (business_unit_type,match_key,revision_no),
  ADD UNIQUE KEY uk_household_batch_match (import_batch_id,match_key),
  ADD UNIQUE KEY uk_household_current_match (business_unit_type,current_match),
  ADD CONSTRAINT ck_household_match_identity CHECK (
    (material_code IS NOT NULL AND CHAR_LENGTH(TRIM(material_code))>0)
    OR (material_code IS NULL AND specification IS NOT NULL AND CHAR_LENGTH(TRIM(specification))>0));

DELIMITER $$
DROP TRIGGER trg_household_price_retire_only$$
CREATE TRIGGER trg_household_price_retire_only BEFORE UPDATE ON lp_household_inquiry_price FOR EACH ROW
BEGIN
  IF NOT (OLD.current_flag=1 AND NEW.current_flag=0 AND NEW.id=OLD.id
    AND NEW.business_unit_type=OLD.business_unit_type AND (BINARY NEW.material_code <=> BINARY OLD.material_code)
    AND (NEW.material_name <=> OLD.material_name) AND (NEW.specification <=> OLD.specification)
    AND NEW.revision_no=OLD.revision_no AND (NEW.previous_price_id <=> OLD.previous_price_id)
    AND NEW.import_batch_id=OLD.import_batch_id AND NEW.source_rows_json=OLD.source_rows_json
    AND NEW.unit_price=OLD.unit_price AND NEW.household_rate=OLD.household_rate
    AND NEW.pricing_fingerprint=OLD.pricing_fingerprint AND NEW.record_fingerprint=OLD.record_fingerprint
    AND NEW.reference_json=OLD.reference_json AND NEW.created_at=OLD.created_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_SOURCE_IMMUTABLE';
  END IF;
END$$
DROP TRIGGER trg_household_price_scope$$
CREATE TRIGGER trg_household_price_scope BEFORE INSERT ON lp_household_inquiry_price FOR EACH ROW
BEGIN
  IF NEW.current_flag<>1 OR NOT EXISTS (SELECT 1 FROM lp_household_inquiry_import_batch b
    WHERE b.id=NEW.import_batch_id AND b.business_unit_type=NEW.business_unit_type AND b.status='PREVIEW') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_VERSION_SCOPE';
  END IF;
  IF (NEW.revision_no=1 AND NEW.previous_price_id IS NOT NULL)
    OR (NEW.revision_no>1 AND (NEW.previous_price_id IS NULL OR NOT EXISTS (
      SELECT 1 FROM lp_household_inquiry_price p WHERE p.id=NEW.previous_price_id
      AND p.business_unit_type=NEW.business_unit_type AND p.match_key=CASE WHEN NEW.material_code IS NULL THEN CONCAT('S:',SHA2(NEW.specification,256)) ELSE CONCAT('C:',NEW.material_code) END
      AND p.revision_no=NEW.revision_no-1))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='HOUSEHOLD_VERSION_SCOPE';
  END IF;
END$$
DROP TRIGGER trg_monthly_household_scope$$
CREATE TRIGGER trg_monthly_household_scope BEFORE INSERT ON lp_material_monthly_price FOR EACH ROW
BEGIN
  IF NEW.household_source_id IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_household_inquiry_price h JOIN lp_household_inquiry_import_batch b ON b.id=h.import_batch_id
    WHERE h.id=NEW.household_source_id AND h.business_unit_type=NEW.business_unit_type AND b.status='PUBLISHED'
      AND (h.material_code=NEW.material_code OR (h.material_code IS NULL
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(NEW.source_evidence_json,'$.document')),'$.match.requestedMaterialCode'))=BINARY NEW.material_code
        AND BINARY JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(NEW.source_evidence_json,'$.document')),'$.match.matchedValue'))=BINARY h.specification))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_HOUSEHOLD_SCOPE';
  END IF;
END$$
DELIMITER ;
