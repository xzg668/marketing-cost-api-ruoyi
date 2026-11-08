-- Saved-version validation and comparison read by version id; existing rows remain unchanged.
CREATE INDEX idx_cost_part_version ON lp_cost_run_part_item (cost_run_version_id);
CREATE INDEX idx_cost_item_version ON lp_cost_run_cost_item (cost_run_version_id,line_no,id);
