-- A monthly header must own the actual BOM rows: EasyData replaces the formal BOM every day.
-- Existing monthly headers can be backfilled only while their original source batch still exists.
CREATE TABLE IF NOT EXISTS lp_quote_bom_monthly_snapshot_detail (
  id BIGINT NOT NULL AUTO_INCREMENT,
  monthly_snapshot_id BIGINT NOT NULL COMMENT 'lp_quote_bom_monthly_snapshot.id',
  line_no INT NOT NULL COMMENT 'Stable order within the monthly snapshot',
  raw_node_json JSON NOT NULL COMMENT 'Complete BOM hierarchy row as used at first quotation',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_monthly_snapshot_line (monthly_snapshot_id, line_no),
  KEY idx_monthly_snapshot_detail (monthly_snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价产品月度BOM冻结明细';
