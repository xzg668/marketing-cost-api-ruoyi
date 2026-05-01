-- =============================================================================
-- V55  路由表 lp_material_price_type 数据清洗：合并 stale 类型到 4 桶模型  2026-04-28
--
-- 强制 connection charset 为 utf8mb4
SET NAMES utf8mb4;
-- =============================================================================
--
-- 业务背景（v1.1 T02）：
--   T01 已把 PriceTypeEnum 简化为 4 桶（FIXED / LINKED / RANGE / MAKE）。
--   但 DB 里 lp_material_price_type.price_type 仍有历史 stale 值：
--     - '结算价' 2 条 → 应合并到 '固定价'（v1 起结算价归 lp_price_fixed_item.source_type=SETTLE）
--     - '原材料联动' 3 条 → 应合并到 '自制件'（v1 起原材料拆解归 lp_make_part_spec.formula_id）
--
--   清洗后 DISTINCT price_type 只剩 4 个：固定价 / 联动价 / 区间价 / 自制件
--   PriceTypeEnum.fromDbText 仍兼容老别名（向前兼容），但表里数据按新模型干净存。
--
-- 影响行数：5 行（2 条结算价 + 3 条原材料联动）
-- 幂等：UPDATE WHERE 旧值；重复跑无副作用
-- =============================================================================

UPDATE lp_material_price_type SET price_type = '固定价' WHERE price_type = '结算价';
UPDATE lp_material_price_type SET price_type = '自制件' WHERE price_type = '原材料联动';
