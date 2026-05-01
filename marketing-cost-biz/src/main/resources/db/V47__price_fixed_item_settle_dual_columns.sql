-- =============================================================================
-- V47  固定价表扩展：家用结算价 双口径列（基准 / 联动）             2026-04-27
--
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   家用结算价9 sheet 同一行有 3 列价格：
--     C5 基准结算价  = 计划价 × 上浮比例（业务核算依据）
--     C6 联动结算价  = 按金属价公式联动算出
--     C8 铜价（基准） = 业务最终取价（部品价格2 形态='家用结算价' 的 VLOOKUP 命中列）
--   公式佐证：=VLOOKUP(A40, 家用结算价9!C:I, 7, 0) → 取的就是 C8（I 列）
--
--   V46 已有 fixed_price 用于装最终取价（C8）；V47 加两个伴生字段保留 C5/C6 业务原始口径，
--   方便财务追溯"为什么这个最终价不是基准价"。
--
-- 本脚本职责：
--   仅加 2 字段，不删不改其他东西。
--
-- 幂等：用存储过程检查列是否存在
-- =============================================================================

DROP PROCEDURE IF EXISTS v47_add_columns;
DELIMITER $$
CREATE PROCEDURE v47_add_columns()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='base_settle_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN base_settle_price DECIMAL(12,6) NULL
        COMMENT 'SETTLE 来源专用：基准结算价（C5 = 计划价 × 上浮比例，业务核算依据）';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema=DATABASE() AND table_name='lp_price_fixed_item' AND column_name='linked_settle_price') THEN
    ALTER TABLE lp_price_fixed_item
      ADD COLUMN linked_settle_price DECIMAL(12,6) NULL
        COMMENT 'SETTLE 来源专用：联动结算价（C6 = 按金属价公式联动算出）';
  END IF;
END$$
DELIMITER ;
CALL v47_add_columns();
DROP PROCEDURE v47_add_columns;

-- V47 结束
