-- I05 复用原生批次记录；保留每次退回范围及原已批准快照，不新增草稿历史表。
ALTER TABLE lp_oa_technical_batch
  DROP CHECK ck_oa_technical_batch_operation,
  ADD CONSTRAINT ck_oa_technical_batch_operation CHECK (operation IN ('I02','I03','I05'));

ALTER TABLE lp_quote_tech_oa_recipient
  ADD COLUMN revision_module_types_json JSON NULL COMMENT '当前修订/重新提交的板块；NULL表示首次分派的全部板块';
