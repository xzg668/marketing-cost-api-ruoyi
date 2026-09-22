-- 只增加确定的外部人员对应、流程绑定和发送/审批证据；不移动历史报价或成本版本。
CREATE TABLE lp_oa_user_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  external_user_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  user_id BIGINT NOT NULL,
  updated_by BIGINT NOT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_oa_user_external (source_system,environment,external_user_id),
  UNIQUE KEY uk_oa_user_internal (source_system,environment,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE lp_oa_technical_flow (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  oa_form_id BIGINT NOT NULL,
  accounting_month CHAR(7) NOT NULL,
  external_document_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  external_flow_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
  finance_message_id BIGINT NULL,
  finance_sequence BIGINT NOT NULL DEFAULT 0,
  finance_ready TINYINT NOT NULL DEFAULT 0,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_oa_technical_scope (oa_form_id,accounting_month),
  UNIQUE KEY uk_oa_technical_external (source_system,environment,external_flow_id),
  CONSTRAINT fk_oa_technical_finance_message FOREIGN KEY (finance_message_id)
    REFERENCES lp_oa_integration_message(id),
  CONSTRAINT ck_oa_technical_finance CHECK (finance_ready IN (0,1)
    AND (finance_ready=0 OR (finance_message_id IS NOT NULL AND finance_sequence>0)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE lp_quote_tech_task
  ADD COLUMN oa_environment VARCHAR(32) COLLATE utf8mb4_bin NULL,
  ADD COLUMN oa_flow_id BIGINT NULL,
  ADD COLUMN oa_assignment_version INT NOT NULL DEFAULT 0,
  ADD COLUMN oa_dispatch_message_id BIGINT NULL,
  ADD CONSTRAINT fk_quote_tech_oa_flow FOREIGN KEY (oa_flow_id) REFERENCES lp_oa_technical_flow(id),
  ADD CONSTRAINT fk_quote_tech_oa_dispatch FOREIGN KEY (oa_dispatch_message_id)
    REFERENCES lp_oa_integration_message(id);

-- 外部任务编号可能在测试和正式环境重复，唯一身份必须包含环境。
ALTER TABLE lp_quote_tech_task
  DROP INDEX uk_quote_tech_task_external_identity,
  DROP COLUMN external_active_lock_key,
  ADD COLUMN external_active_lock_key VARCHAR(320) GENERATED ALWAYS AS
    (CASE WHEN active_flag=1 AND external_system IS NOT NULL AND external_task_id IS NOT NULL
      THEN CONCAT(external_system,':',COALESCE(oa_environment,'LEGACY'),':',external_task_id) ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_quote_tech_task_external_identity (external_active_lock_key);

ALTER TABLE lp_quote_tech_submission
  ADD COLUMN outbound_message_id BIGINT NULL,
  ADD COLUMN sent_at DATETIME(3) NULL,
  ADD COLUMN decision_message_id BIGINT NULL,
  ADD COLUMN decided_at DATETIME(3) NULL,
  ADD UNIQUE KEY uk_tech_submission_outbound (outbound_message_id),
  ADD CONSTRAINT fk_tech_submission_outbound FOREIGN KEY (outbound_message_id)
    REFERENCES lp_oa_integration_message(id),
  ADD CONSTRAINT fk_tech_submission_decision FOREIGN KEY (decision_message_id)
    REFERENCES lp_oa_integration_message(id);

-- HTTP requestId 去重和业务 eventId 去重不同；换 requestId 重发也只能形成一个业务结论。
CREATE TABLE lp_oa_workflow_event (
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  event_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  semantic_hash CHAR(64) NOT NULL,
  message_id BIGINT NOT NULL,
  result_json JSON NULL,
  PRIMARY KEY (source_system,environment,event_id),
  CONSTRAINT fk_oa_workflow_event_message FOREIGN KEY (message_id) REFERENCES lp_oa_integration_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
