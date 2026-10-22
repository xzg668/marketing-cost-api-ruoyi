-- PRC-04：保留完整展开的原稿公式。仅扩大容量，不修改历史公式和值。
-- 可重复执行；上线时按迁移流程执行。本任务只在独立验收库执行。
ALTER TABLE lp_price_linked_item MODIFY formula_expr MEDIUMTEXT NULL COMMENT '可执行公式；ORIGINAL_V1保留原稿完整依赖与ROUND/INT步骤';
