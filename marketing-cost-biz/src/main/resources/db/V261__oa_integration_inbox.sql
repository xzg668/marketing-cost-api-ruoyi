-- OA 接收箱：原始业务报文、逐次处理记录和稳定来源单据绑定各负其责。
-- 默认关闭接口；先执行本迁移，再配置 integration.oa 的环境、调用方和密钥。
CREATE TABLE IF NOT EXISTS lp_oa_integration_message (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  direction VARCHAR(16) NOT NULL DEFAULT 'INBOUND',
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  interface_type VARCHAR(32) NOT NULL,
  schema_version INT NOT NULL,
  occurred_at VARCHAR(40) NOT NULL,
  raw_payload LONGTEXT NOT NULL,
  payload_hash CHAR(64) NOT NULL,
  allowed_business_units VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  lease_token CHAR(36) NULL,
  lease_until DATETIME(3) NULL,
  error_stage VARCHAR(32) NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  result_json JSON NULL,
  received_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at DATETIME(3) NULL,
  CONSTRAINT uk_oa_message_request UNIQUE (source_system, environment, direction, request_id),
  CONSTRAINT ck_oa_message_json CHECK (JSON_VALID(raw_payload)),
  CONSTRAINT ck_oa_message_status CHECK (status IN ('RECEIVED','PROCESSING','WAITING_HANDLER','PROCESSED','REJECTED','FAILED')),
  INDEX idx_oa_message_poll (status, next_attempt_at, lease_until, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS lp_oa_integration_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  message_id BIGINT NOT NULL,
  attempt_no INT NOT NULL,
  mapping_version VARCHAR(32) NOT NULL,
  stage VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at DATETIME(3) NULL,
  duration_ms BIGINT NULL,
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(512) NULL,
  CONSTRAINT uk_oa_attempt UNIQUE (message_id, attempt_no),
  CONSTRAINT fk_oa_attempt_message FOREIGN KEY (message_id) REFERENCES lp_oa_integration_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 请求幂等不能替代单据幂等：不同请求可推同一张 OA 单；该行也是单据并发更新的锁。
CREATE TABLE IF NOT EXISTS lp_oa_quote_document (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  environment VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
  external_document_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  oa_form_id BIGINT NULL,
  source_version BIGINT NOT NULL DEFAULT 0,
  canonical_hash CHAR(64) NULL,
  latest_message_id BIGINT NULL,
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  CONSTRAINT uk_oa_document_source UNIQUE (source_system, environment, external_document_id),
  CONSTRAINT uk_oa_document_form UNIQUE (oa_form_id),
  CONSTRAINT fk_oa_document_message FOREIGN KEY (latest_message_id) REFERENCES lp_oa_integration_message(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

DROP TRIGGER IF EXISTS trg_oa_message_original_immutable;
DROP TRIGGER IF EXISTS trg_oa_attempt_finished_immutable;
DELIMITER $$
CREATE TRIGGER trg_oa_message_original_immutable BEFORE UPDATE ON lp_oa_integration_message
FOR EACH ROW
BEGIN
  IF NOT (BINARY OLD.source_system <=> BINARY NEW.source_system)
    OR NOT (BINARY OLD.environment <=> BINARY NEW.environment)
    OR NOT (BINARY OLD.direction <=> BINARY NEW.direction)
    OR NOT (BINARY OLD.request_id <=> BINARY NEW.request_id)
    OR NOT (OLD.interface_type <=> NEW.interface_type)
    OR NOT (OLD.schema_version <=> NEW.schema_version)
    OR NOT (OLD.occurred_at <=> NEW.occurred_at)
    OR NOT (BINARY OLD.raw_payload <=> BINARY NEW.raw_payload)
    OR NOT (OLD.payload_hash <=> NEW.payload_hash)
    OR NOT (OLD.allowed_business_units <=> NEW.allowed_business_units)
    OR NOT (OLD.received_at <=> NEW.received_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'OA original message is immutable';
  END IF;
END$$
CREATE TRIGGER trg_oa_attempt_finished_immutable BEFORE UPDATE ON lp_oa_integration_attempt
FOR EACH ROW
BEGIN
  IF OLD.finished_at IS NOT NULL OR OLD.message_id <> NEW.message_id
    OR OLD.attempt_no <> NEW.attempt_no OR OLD.mapping_version <> NEW.mapping_version
    OR OLD.started_at <> NEW.started_at THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'OA completed attempt is immutable';
  END IF;
END$$
DELIMITER ;
