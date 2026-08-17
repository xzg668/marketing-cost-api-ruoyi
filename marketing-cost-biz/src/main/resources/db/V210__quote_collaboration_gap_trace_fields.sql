-- QCBP-13：只扩展本期新建的协作缺口表，不修改既有报价、BOM、价格或核算业务表。
ALTER TABLE `lp_quote_collaboration_gap`
  ADD COLUMN `bom_quantity` DECIMAL(20,8) NULL COMMENT '当前BOM路径累计到顶层用量' AFTER `bom_path`,
  ADD COLUMN `bom_unit` VARCHAR(32) NULL COMMENT '当前BOM路径用量单位' AFTER `bom_quantity`,
  ADD COLUMN `accounting_month` CHAR(7) NULL COMMENT '缺价检查核算月份快照' AFTER `bom_unit`,
  ADD COLUMN `applicable_org_code` VARCHAR(64) NULL COMMENT '缺价检查适用组织快照' AFTER `accounting_month`,
  ADD KEY `idx_collaboration_gap_scope` (`accounting_month`, `applicable_org_code`, `gap_status`);
