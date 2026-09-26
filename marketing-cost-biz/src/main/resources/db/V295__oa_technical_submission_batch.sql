-- 一个 I02 分派批次或一位技术员的一次 I03 提交，只发送一份 OA 报文。
-- 产品仍沿用现有任务、当前草稿和提交快照；这里不保存草稿历史。
CREATE TABLE lp_oa_technical_batch (
  id CHAR(36) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  operation VARCHAR(16) NOT NULL,
  oa_form_id BIGINT NOT NULL,
  actor_user_id BIGINT NOT NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  input_fingerprint CHAR(64) NOT NULL,
  status VARCHAR(24) NOT NULL DEFAULT 'PREPARED',
  request_json JSON NOT NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_oa_technical_batch_request (operation,actor_user_id,request_key),
  KEY idx_oa_technical_batch_form (oa_form_id,actor_user_id,created_at),
  CONSTRAINT ck_oa_technical_batch_operation CHECK (operation IN ('I02','I03')),
  CONSTRAINT ck_oa_technical_batch_status CHECK
    (status IN ('PREPARED','SENDING','OA_ACCEPTED','SUCCESS','REJECTED','NOT_SENT','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 既有消息保存逐产品关联及审计证据，实际网络发送由对应批次统一完成。
ALTER TABLE lp_oa_integration_message
  ADD COLUMN technical_batch_id CHAR(36) COLLATE utf8mb4_bin NULL,
  ADD KEY idx_oa_message_technical_batch (technical_batch_id),
  ADD CONSTRAINT fk_oa_message_technical_batch FOREIGN KEY (technical_batch_id)
    REFERENCES lp_oa_technical_batch(id);
