-- BOM code is optional only when an exact specification identifies the price.
-- Existing facts, quoted amounts, links and scope/immutability checks are preserved.
ALTER TABLE lp_material_monthly_price DROP CHECK ck_monthly_price_identity,
  ADD CONSTRAINT ck_monthly_price_identity CHECK (
    business_unit_type IN ('COMMERCIAL','HOUSEHOLD')
    AND (material_code<>'' OR COALESCE(TRIM(specification),'')<>'') AND pricing_unit<>''
    AND pricing_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$' AND LEFT(pricing_month,4)<>'0000'
    AND currency REGEXP '^[A-Z]{3}$' AND tax_basis IN ('EXCLUDED','INCLUDED') AND source_type<>'');

DELIMITER $$
DROP TRIGGER IF EXISTS trg_prepare_monthly_scope_insert$$
CREATE TRIGGER trg_prepare_monthly_scope_insert BEFORE INSERT ON lp_price_prepare_item FOR EACH ROW
BEGIN
  IF NEW.monthly_price_id IS NOT NULL AND NEW.monthly_pricing_type IS NOT NULL AND NOT EXISTS (
    SELECT 1 FROM lp_material_monthly_price p
    WHERE p.id=NEW.monthly_price_id AND BINARY p.business_unit_type=BINARY NEW.business_unit_type
      AND ((COALESCE(TRIM(NEW.material_code),'')<>'' AND BINARY p.material_code=BINARY NEW.material_code)
        OR (COALESCE(TRIM(NEW.material_code),'')='' AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(p.first_use_json,'$.document')),'$.specificationOnly'))='true'
          AND EXISTS (SELECT 1 FROM lp_bom_costing_row b WHERE b.id=NEW.bom_row_id
            AND COALESCE(TRIM(b.material_code),'')='' AND BINARY b.material_spec=BINARY p.specification
            AND BINARY b.business_unit_type=BINARY NEW.business_unit_type
            AND (b.oa_form_item_id <=> NEW.oa_form_item_id)
            AND BINARY b.top_product_code=BINARY NEW.top_product_code
            AND BINARY b.period_month=BINARY NEW.period_month))) AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
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
      AND ((COALESCE(TRIM(NEW.material_code),'')<>'' AND BINARY p.material_code=BINARY NEW.material_code)
        OR (COALESCE(TRIM(NEW.material_code),'')='' AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(p.first_use_json,'$.document')),'$.specificationOnly'))='true'
          AND EXISTS (SELECT 1 FROM lp_bom_costing_row b WHERE b.id=NEW.bom_row_id
            AND COALESCE(TRIM(b.material_code),'')='' AND BINARY b.material_spec=BINARY p.specification
            AND BINARY b.business_unit_type=BINARY NEW.business_unit_type
            AND (b.oa_form_item_id <=> NEW.oa_form_item_id)
            AND BINARY b.top_product_code=BINARY NEW.top_product_code
            AND BINARY b.period_month=BINARY NEW.period_month))) AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
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
      AND ((COALESCE(TRIM(NEW.part_code),'')<>'' AND BINARY p.material_code=BINARY NEW.part_code)
        OR (COALESCE(TRIM(NEW.part_code),'')='' AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(p.first_use_json,'$.document')),'$.specificationOnly'))='true'
          AND EXISTS (SELECT 1 FROM lp_price_prepare_item prepared JOIN lp_bom_costing_row b ON b.id=prepared.bom_row_id
            WHERE prepared.id=NEW.price_prepare_item_id AND prepared.monthly_price_id=p.id
              AND COALESCE(TRIM(prepared.material_code),'')='' AND COALESCE(TRIM(b.material_code),'')=''
              AND BINARY b.material_spec=BINARY p.specification AND b.id=NEW.bom_row_id
              AND BINARY prepared.business_unit_type=BINARY NEW.business_unit_type
              AND (prepared.oa_form_item_id <=> NEW.oa_form_item_id)))) AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
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
      AND ((COALESCE(TRIM(NEW.part_code),'')<>'' AND BINARY p.material_code=BINARY NEW.part_code)
        OR (COALESCE(TRIM(NEW.part_code),'')='' AND JSON_UNQUOTE(JSON_EXTRACT(JSON_UNQUOTE(JSON_EXTRACT(p.first_use_json,'$.document')),'$.specificationOnly'))='true'
          AND EXISTS (SELECT 1 FROM lp_price_prepare_item prepared JOIN lp_bom_costing_row b ON b.id=prepared.bom_row_id
            WHERE prepared.id=NEW.price_prepare_item_id AND prepared.monthly_price_id=p.id
              AND COALESCE(TRIM(prepared.material_code),'')='' AND COALESCE(TRIM(b.material_code),'')=''
              AND BINARY b.material_spec=BINARY p.specification AND b.id=NEW.bom_row_id
              AND BINARY prepared.business_unit_type=BINARY NEW.business_unit_type
              AND (prepared.oa_form_item_id <=> NEW.oa_form_item_id)))) AND BINARY p.pricing_type=BINARY NEW.monthly_pricing_type
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
