-- 固定价、技术补录等报价也保存本次采用的财务基价。旧批次保持原样。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS v316_price_prepare_finance_basis;
DELIMITER //
CREATE PROCEDURE v316_price_prepare_finance_basis()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_prepare_batch' AND column_name='finance_factor_snapshot_json') THEN
    ALTER TABLE lp_price_prepare_batch ADD COLUMN finance_factor_snapshot_json JSON NULL
      COMMENT '本次财务基价及发布来源，历史批次不得用当前基价回填';
  END IF;
END//
DELIMITER ;
CALL v316_price_prepare_finance_basis();
DROP PROCEDURE v316_price_prepare_finance_basis;
