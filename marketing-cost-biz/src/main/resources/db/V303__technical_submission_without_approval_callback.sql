-- OA 不再发送 TECH_APPROVED。资料齐全由成功提交记录决定，报价员依据 OA 待办主动确认。
-- 保留历史审批消息及序号作追溯，不再要求它们作为资料可核算的前置条件。
ALTER TABLE lp_oa_technical_flow
  DROP CHECK ck_oa_technical_finance,
  ADD CONSTRAINT ck_oa_technical_finance CHECK (finance_ready IN (0,1));
