-- I04/I08 每个原流程共用连续序号；事件正文继续保存在统一消息表。
CREATE TABLE lp_oa_workflow_state (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
 environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
 workflow_request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
 applied_version BIGINT NOT NULL DEFAULT 0,
 form_version BIGINT NOT NULL DEFAULT 0,
 observed_form_version BIGINT NOT NULL DEFAULT 0,
 state VARCHAR(32) NOT NULL DEFAULT 'WAITING',
 active_work_items_json JSON NOT NULL,
 reason VARCHAR(512),
 sync_error VARCHAR(512),
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 UNIQUE KEY uk_oa_workflow_scope(source_system,environment,workflow_request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE lp_oa_workflow_notification (
 flow_id BIGINT NOT NULL,
 sequence_no BIGINT NOT NULL,
 message_id BIGINT NOT NULL,
 semantic_hash CHAR(64) NOT NULL,
 PRIMARY KEY(flow_id,sequence_no),
 UNIQUE KEY uk_oa_notification_message(message_id),
 FOREIGN KEY(flow_id) REFERENCES lp_oa_workflow_state(id),
 FOREIGN KEY(message_id) REFERENCES lp_oa_integration_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
-- 一人一任务编号，不使用产品任务主键冒充每个技术员的任务身份。
ALTER TABLE lp_quote_tech_oa_recipient ADD COLUMN integration_task_id VARCHAR(128) COLLATE utf8mb4_bin NULL,
 ADD UNIQUE KEY uk_tech_recipient_integration_task(integration_task_id);
UPDATE lp_quote_tech_oa_recipient SET integration_task_id=CONCAT('T-',id) WHERE integration_task_id IS NULL;

ALTER TABLE lp_quote_tech_oa_recipient MODIFY COLUMN outbound_message_id BIGINT NULL;
ALTER TABLE lp_quote_tech_module ADD COLUMN oa_edit_allowed TINYINT NOT NULL DEFAULT 1;
