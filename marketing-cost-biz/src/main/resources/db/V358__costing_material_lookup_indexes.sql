-- This wide legacy table is close to MySQL's 65535-byte row limit. Keep the generated
-- index expression to eight characters; the query still checks the complete trimmed
-- model with its original collation, so prefix collisions never become matches.
CREATE INDEX idx_raw_active_model_prefix ON lp_material_master_raw
  (organization_code, active_flag, (LEFT(TRIM(material_model), 8)), material_code);

-- Source revision checks intentionally observe all organizations for the actual quoted codes.
CREATE INDEX idx_raw_material_active ON lp_material_master_raw (material_code, active_flag);
