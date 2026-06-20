-- V171: 放宽成本费用项备注长度。
--
-- 单产品核算允许缺价继续时，会把缺价/缺价格类型诊断写入费用项 remark。
-- V56 初始列为 VARCHAR(255)，长诊断在 MySQL 严格模式下会导致 Data too long 并回滚核算。

ALTER TABLE lp_cost_run_cost_item
  MODIFY COLUMN remark TEXT NULL COMMENT '缺率/异常/缺价说明';
