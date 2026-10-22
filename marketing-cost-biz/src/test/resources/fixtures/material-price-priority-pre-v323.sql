-- The shared baseline omits V51. Restore its material-priority key before V323.
-- This fixture runs only inside a newly created Testcontainers database.
ALTER TABLE lp_material_price_type
  ADD UNIQUE KEY uk_route_period_mat_type_bu (period, material_code, price_type, business_unit_type);
