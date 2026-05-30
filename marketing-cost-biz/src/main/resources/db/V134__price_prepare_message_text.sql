-- =============================================================================
-- V134: 价格准备说明字段扩容
-- -----------------------------------------------------------------------------
-- 月度调价批量价格准备时，缺价/异常说明可能包含多条部品诊断信息，VARCHAR(1000)
-- 会导致明细写入失败。说明字段不参与索引，扩为 TEXT，避免诊断信息影响主流程。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE lp_price_prepare_batch
  MODIFY COLUMN message TEXT DEFAULT NULL COMMENT '批次摘要';

ALTER TABLE lp_price_prepare_item
  MODIFY COLUMN message TEXT DEFAULT NULL COMMENT '明细说明';

ALTER TABLE lp_price_prepare_gap
  MODIFY COLUMN message TEXT DEFAULT NULL COMMENT '给业务/技术员看的说明';
