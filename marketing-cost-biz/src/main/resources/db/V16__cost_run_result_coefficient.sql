-- =====================================================================
-- V16：lp_cost_run_result 加 product_attr_coefficient + adjusted_manufacture_cost
--   背景：Excel 见机表3 第 54 行 "产品属性 | 标准品 | 1.0 | 调整后制造成本=111.316"
--         coefficient 已在 V11 加到 lp_product_property；本迁移把"试算时实际命中的系数
--         与调整后的制造成本"持久化到结果表，保证审计追溯不依赖外部表事后查询。
--   口径：adjusted_manufacture_cost = manufacture_cost × product_attr_coefficient
--         三项费用基数（管理/销售/财务）= adjusted_manufacture_cost（非原 manufacture_cost）
--   兼容：默认 1.0000 / NULL 允许，老数据不需回填即可保留语义。
-- =====================================================================

ALTER TABLE `lp_cost_run_result`
  ADD COLUMN `product_attr_coefficient` DECIMAL(10,4) NULL DEFAULT 1.0000
    COMMENT '试算时命中的产品属性系数（lp_product_property.coefficient 快照）',
  ADD COLUMN `adjusted_manufacture_cost` DECIMAL(18,6) NULL
    COMMENT '调整后制造成本 = 制造成本 × 产品属性系数；三项费用基数';
