-- 报价员/管理员可在正式分派前进入真实补录表单；不新增业务表，也不伪造技术员责任人。
ALTER TABLE lp_quote_tech_task
  DROP CHECK ck_quote_tech_task_status,
  MODIFY assignee_user_id BIGINT NULL COMMENT '默认技术负责人ID；未分派草稿为空',
  MODIFY assignee_name VARCHAR(128) NULL COMMENT '默认技术负责人；未分派草稿为空',
  ADD CONSTRAINT ck_quote_tech_task_status CHECK (task_status IN
    ('UNASSIGNED','PENDING','IN_PROGRESS','PREPARED','SUBMITTED','PARTIALLY_RETURNED','APPROVED','CANCELLED')),
  ADD CONSTRAINT ck_quote_tech_task_assignee_pair CHECK
    ((assignee_user_id IS NULL AND assignee_name IS NULL)
      OR (assignee_user_id IS NOT NULL AND assignee_name IS NOT NULL)),
  ADD CONSTRAINT ck_quote_tech_task_unassigned_owner CHECK
    (assignee_user_id IS NOT NULL OR task_status IN ('UNASSIGNED','CANCELLED'));
