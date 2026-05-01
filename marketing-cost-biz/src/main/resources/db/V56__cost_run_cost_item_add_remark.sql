-- =============================================================================
-- V56: lp_cost_run_cost_item 增加 remark 列（T10 缺率记账）
-- -----------------------------------------------------------------------------
-- 背景：CostRunCostItemServiceImpl 在费率（quality_loss / manufacture / 三项费用 /
--      department_fund / other_expense）查不到时已 silently 写 amount=NULL，
--      但用户看不到原因，无法判断是"该项不该有"还是"配置缺失"。
--
-- 改动：单纯加列，nullable 默认 NULL，无数据迁移。
--      Java 层 DTO/Entity 同步加字段；保存时写"lp_xxx_rate 表无 businessUnit=YYY 配置"
--      之类的提示，前端 / Excel 导出可直接显示。
--
-- 兼容：纯加列、向后兼容；现有 35 行旧数据 remark 默认 NULL。
-- =============================================================================

ALTER TABLE lp_cost_run_cost_item
  ADD COLUMN remark VARCHAR(255) DEFAULT NULL COMMENT '缺率/异常说明 (T10)' AFTER source_id;
