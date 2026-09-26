-- 明确区分“退回结果待确认”和“技术领导审批中”，发送期间仍禁止编辑。
ALTER TABLE lp_quote_tech_task
  DROP CHECK ck_quote_tech_task_status,
  ADD CONSTRAINT ck_quote_tech_task_status CHECK (task_status IN
    ('UNASSIGNED','PENDING','IN_PROGRESS','PREPARED','SUBMITTED','RETURN_PENDING','PARTIALLY_RETURNED','APPROVED','CANCELLED'));
