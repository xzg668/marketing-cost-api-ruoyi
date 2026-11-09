-- A stopped test price remains readable by its historical ID but is never reused.
-- No new table, revision system, automatic invalidation job or finance workflow.
ALTER TABLE lp_material_monthly_price
  ADD COLUMN disabled TINYINT NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_monthly_price_disabled CHECK (disabled IN (0,1)),
  DROP INDEX uk_monthly_price_identity,
  ADD UNIQUE KEY uk_monthly_price_identity (family_hash,
    ((CASE WHEN disabled=0 THEN applicability_hash ELSE NULL END)),
    invoice_revision,finance_revision,household_revision);

DELIMITER $$
DROP TRIGGER trg_monthly_price_no_update$$
CREATE TRIGGER trg_monthly_price_no_update BEFORE UPDATE ON lp_material_monthly_price FOR EACH ROW
BEGIN
  IF NOT (
    (NEW.id <=> OLD.id) AND (NEW.family_hash <=> OLD.family_hash)
    AND (NEW.applicability_hash <=> OLD.applicability_hash) AND (BINARY NEW.applicability_json <=> BINARY OLD.applicability_json)
    AND (NEW.organization_mask <=> OLD.organization_mask) AND (BINARY NEW.pricing_type <=> BINARY OLD.pricing_type)
    AND (NEW.unit_price <=> OLD.unit_price) AND (NEW.household_rate <=> OLD.household_rate)
    AND (NEW.source_evidence_hash <=> OLD.source_evidence_hash) AND (BINARY NEW.source_evidence_json <=> BINARY OLD.source_evidence_json)
    AND (NEW.household_source_id <=> OLD.household_source_id) AND (BINARY NEW.first_use_json <=> BINARY OLD.first_use_json)
    AND (BINARY NEW.created_by <=> BINARY OLD.created_by) AND (NEW.created_at <=> OLD.created_at)
    AND (BINARY NEW.pricing_month <=> BINARY OLD.pricing_month) AND (BINARY NEW.business_unit_type <=> BINARY OLD.business_unit_type)
    AND (BINARY NEW.material_code <=> BINARY OLD.material_code) AND (BINARY NEW.specification <=> BINARY OLD.specification)
    AND (BINARY NEW.pricing_unit <=> BINARY OLD.pricing_unit) AND (BINARY NEW.currency <=> BINARY OLD.currency)
    AND (BINARY NEW.tax_basis <=> BINARY OLD.tax_basis) AND (BINARY NEW.requested_supplier_code <=> BINARY OLD.requested_supplier_code)
    AND (BINARY NEW.source_type <=> BINARY OLD.source_type) AND (NEW.source_record_id <=> OLD.source_record_id)
    AND (BINARY NEW.supplier_code <=> BINARY OLD.supplier_code) AND (BINARY NEW.supplier_name <=> BINARY OLD.supplier_name)
    AND (NEW.household_revision <=> OLD.household_revision)
    AND (NEW.invoice_revision <=> OLD.invoice_revision) AND (NEW.finance_revision <=> OLD.finance_revision)
    AND (
      ((NEW.disabled <=> OLD.disabled) AND OLD.superseded_by_finance_batch_id IS NULL
        AND NEW.superseded_by_finance_batch_id IS NOT NULL AND NEW.superseded_by_finance_batch_id > 0)
      OR (OLD.disabled=0 AND NEW.disabled=1
        AND (NEW.superseded_by_finance_batch_id <=> OLD.superseded_by_finance_batch_id))
    )
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='MPP_MONTHLY_PRICE_IMMUTABLE';
  END IF;
END$$
DELIMITER ;
