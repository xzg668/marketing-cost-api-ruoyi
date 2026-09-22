-- 模块分工沿用原产品任务；个人 OA 待办只记录分派与外部状态，不复制补录资料。
ALTER TABLE lp_quote_tech_module
  ADD COLUMN assignee_user_id BIGINT NULL,
  ADD COLUMN assignee_name VARCHAR(64) NULL,
  ADD KEY idx_tech_module_assignee (assignee_user_id,product_id),
  ADD CONSTRAINT ck_tech_module_assignee CHECK
    ((assignee_user_id IS NULL AND assignee_name IS NULL)
      OR (assignee_user_id > 0 AND assignee_name IS NOT NULL));

CREATE TABLE lp_quote_tech_oa_recipient (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  task_id BIGINT NOT NULL,
  assignment_version INT NOT NULL,
  assignee_user_id BIGINT NOT NULL,
  assignee_name VARCHAR(64) NOT NULL,
  external_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  action VARCHAR(16) NOT NULL DEFAULT 'ASSIGN',
  module_types_json JSON NOT NULL,
  outbound_message_id BIGINT NOT NULL,
  external_task_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  dispatch_status VARCHAR(16) NOT NULL DEFAULT 'QUEUED',
  todo_status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
  department_name VARCHAR(128) NULL,
  leader_external_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  leader_name VARCHAR(128) NULL,
  latest_submission_id BIGINT NULL,
  submission_round INT NOT NULL DEFAULT 0,
  callback_sequence BIGINT NOT NULL DEFAULT 0,
  return_reason VARCHAR(1000) NULL,
  return_message_id BIGINT NULL,
  return_requested_by BIGINT NULL,
  active_flag TINYINT NOT NULL DEFAULT 0,
  active_person_key VARCHAR(96) GENERATED ALWAYS AS (CASE WHEN active_flag=1 THEN CONCAT(task_id,':',assignee_user_id) ELSE NULL END) STORED,
  row_version INT NOT NULL DEFAULT 0,
  last_error VARCHAR(512) NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_tech_oa_active_person (active_person_key),
  UNIQUE KEY uk_tech_oa_recipient (task_id,assignment_version,assignee_user_id),
  UNIQUE KEY uk_tech_oa_recipient_message (outbound_message_id),
  UNIQUE KEY uk_tech_oa_recipient_external (task_id,assignment_version,external_task_id),
  CONSTRAINT fk_tech_oa_recipient_task FOREIGN KEY (task_id) REFERENCES lp_quote_tech_task(id),
  CONSTRAINT fk_tech_person_return_message FOREIGN KEY (return_message_id) REFERENCES lp_oa_integration_message(id),
  CONSTRAINT fk_tech_oa_recipient_message FOREIGN KEY (outbound_message_id) REFERENCES lp_oa_integration_message(id),
  CONSTRAINT ck_tech_oa_recipient_version CHECK (assignment_version > 0),
  CONSTRAINT ck_tech_oa_recipient_action CHECK (action IN ('ASSIGN','CANCEL')),
  CONSTRAINT ck_tech_oa_recipient_modules CHECK (JSON_TYPE(module_types_json)='ARRAY'),
  CONSTRAINT ck_tech_oa_recipient_dispatch CHECK (dispatch_status IN ('QUEUED','UNKNOWN','CONFIRMED','REJECTED')),
  CONSTRAINT ck_tech_oa_recipient_todo CHECK (todo_status IN ('WAITING','OPEN','PREPARED','SUBMITTED','DONE','RETURN_PENDING','CANCELLED','SUPERSEDED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 既有整产品历史不改写；新提交显式归属人员分支，轮次不再由全产品共用。
ALTER TABLE lp_quote_tech_submission
  ADD COLUMN recipient_id BIGINT NULL,
  ADD COLUMN module_types_json JSON NULL,
  ADD COLUMN leader_external_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  DROP INDEX uk_quote_tech_submission_round,
  ADD UNIQUE KEY uk_quote_tech_submission_person_round (task_id,assignee_user_id,submission_round),
  ADD CONSTRAINT fk_tech_submission_recipient FOREIGN KEY (recipient_id) REFERENCES lp_quote_tech_oa_recipient(id);

ALTER TABLE lp_quote_tech_oa_recipient
  ADD CONSTRAINT fk_tech_recipient_submission FOREIGN KEY (latest_submission_id) REFERENCES lp_quote_tech_submission(id);

ALTER TABLE lp_oa_technical_flow
  ADD COLUMN finance_user_id BIGINT NULL,
  ADD COLUMN finance_confirmed_fingerprint CHAR(64) NULL,
  ADD COLUMN finance_confirmed_by BIGINT NULL,
  ADD COLUMN finance_confirmed_at DATETIME(3) NULL;

-- 新字段也属于冻结提交身份；不改写 V260 已应用的历史触发器。
DELIMITER $$
CREATE TRIGGER trg_tech_submission_person_immutable BEFORE UPDATE ON lp_quote_tech_submission
FOR EACH ROW
BEGIN
  IF NOT (NEW.recipient_id <=> OLD.recipient_id)
     OR NOT (NEW.module_types_json <=> OLD.module_types_json)
     OR NOT (NEW.leader_external_id <=> OLD.leader_external_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='PERSON_SUBMISSION_IDENTITY_IMMUTABLE';
  END IF;
END$$
DELIMITER ;
