-- =====================================================================
-- V11：lp_product_property 加 coefficient（产品属性系数）
--   背景：Excel 见机表3 第 54 行 "产品属性 | 标准品 | 1.0 | 111.316"
--         系数会作用于"调整后制造成本 = 制造成本 × 系数"，再作为三项费用基数。
--         样本中标准品 = 1，非标品/优化品 ≠ 1。
--   兼容：默认值 1，老数据相当于"标准品"，业务行为保持不变。
--   验收：金标产品 1079900000536 是标准品 → coefficient=1，
--         adjustedManufactureCost = manufactureCost = 111.316。
-- =====================================================================

ALTER TABLE `lp_product_property`
  ADD COLUMN `coefficient` DECIMAL(10,4) NOT NULL DEFAULT 1.0000
    COMMENT '产品属性系数：调整后制造成本 = 制造成本 × 系数（标准品=1）';

-- 历史数据兜底：现存 5 条都按"标准品"计，coefficient=1（DEFAULT 已覆盖，此处显式幂等）
UPDATE `lp_product_property` SET `coefficient` = 1.0000 WHERE `coefficient` IS NULL;
