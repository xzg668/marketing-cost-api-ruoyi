-- V58：lp_aux_subject.unit_price 精度从 decimal(18,2) 升到 decimal(18,6)
--
-- 背景（OA-GOLDEN-001 对账发现）：
--   Excel 见机表"部品价格2"sheet 里 SHF-AA-79酸洗=0.6732 / 辅料=1.1495 是 4 位小数；
--   导入时被 MySQL 静默截断/四舍五入到 0.67 / 1.15，
--   导致系统材料费比 Excel 少 0.0027（合计 -0.0027 元/件）。
--
-- 横向对齐：
--   lp_cost_run_part_item.unit_price        decimal(18,6)  ✓
--   lp_cost_run_cost_item.amount            decimal(18,6)  ✓
--   lp_aux_rate_item.float_rate             decimal(10,4)  ✓
--   lp_aux_subject.unit_price               decimal(18,2)  ❌ 偏低，本次升到 (18,6)
--
-- 影响：
--   - 历史已落库行：原值会被 MySQL 自动 widen，0.67 仍是 0.67（无信息可恢复）；
--     业务方需重新导入 Excel 才能拿回 4 位精度（0.67 → 0.6732）。
--   - 应用代码：BigDecimal 透明，无需改动。
--
-- Migration 等价于扩列，无数据丢失风险，可重入（MODIFY 同样列类型 = no-op）。

ALTER TABLE `lp_aux_subject`
  MODIFY COLUMN `unit_price` decimal(18,6) DEFAULT NULL COMMENT '辅料单价（元，6 位小数对齐其他价格字段）';
