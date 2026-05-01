-- =============================================================================
-- V52  联动价 calc_item 数值精度扩到 6 位                                  2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景：
--   trace_json 里 result 实际有 6 位精度（如 0.124314513274336）；
--   但 part_unit_price/part_amount 字段定义 DECIMAL(18,2)，只存到 0.12 → 精度损失。
--   业务在前端"联动价计算"列表看到的单价是 0.12，跟联动价格表金标 0.124336 对不齐，
--   容易让业务误以为算错。
--
--   修法：3 个数值列统一扩到 DECIMAL(18,6)：
--     - bom_qty           (用量，克级粒度需要 6 位)
--     - part_unit_price   (单价，公式求值结果 6 位)
--     - part_amount       (金额 = unit × qty，避免传递精度损失)
--
-- 幂等：ALTER MODIFY 重复跑不报错（精度相同则 no-op）
-- =============================================================================

ALTER TABLE lp_price_linked_calc_item
  MODIFY COLUMN bom_qty         DECIMAL(18,6) NULL COMMENT 'BOM 用量（克级粒度）',
  MODIFY COLUMN part_unit_price DECIMAL(18,6) NULL COMMENT '部品单价（联动公式求值，6 位）',
  MODIFY COLUMN part_amount     DECIMAL(18,6) NULL COMMENT '部品金额 = 单价 × 用量';
