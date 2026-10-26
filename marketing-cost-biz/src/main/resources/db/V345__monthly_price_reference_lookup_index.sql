-- MPP-11: read-only reverse lookup uses existing immutable cost snapshots, including dependencies.
-- Additive index only; no row updates/backfill and no extra monthly-price/reference table.
CREATE INDEX idx_quote_cost_monthly_lookup
  ON lp_quote_cost_run_version (business_unit_type, pricing_month, id);
