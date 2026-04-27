-- =====================================================================
-- V26: 联动价改造 —— lp_price_linked_calc_item 新增 trace_json 列
--   trace_json 存单条联动部品每次计算的求值 trace（规范化表达式/变量赋值/步骤/结果）
--   前端"查看 Trace drawer"依赖此列回溯历史计算过程。
-- =====================================================================

ALTER TABLE `lp_price_linked_calc_item`
  ADD COLUMN `trace_json` LONGTEXT DEFAULT NULL
    COMMENT '最近一次计算 trace JSON：{normalizedExpr, variables, steps, result/error}';
