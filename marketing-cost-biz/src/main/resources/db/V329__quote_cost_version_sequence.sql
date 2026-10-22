-- Allocate daily cost version numbers without COUNT(*) races or holding a global
-- sequence lock for the duration of a product's costing transaction.
CREATE TABLE IF NOT EXISTS lp_quote_cost_version_sequence (
  sequence_day DATE NOT NULL,
  last_number BIGINT NOT NULL,
  PRIMARY KEY (sequence_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价成本版本每日编号';
