-- PDF 核验只新增任务和不可变证据；既有 Excel 来源和冻结历史保持原样。
CREATE TABLE IF NOT EXISTS lp_drawing_verification_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  source_system VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
  external_task_id VARCHAR(160) COLLATE utf8mb4_bin NULL,
  oa_no VARCHAR(255) NOT NULL,
  oa_form_item_id BIGINT NOT NULL,
  business_unit_type VARCHAR(32) NOT NULL,
  material_org_code VARCHAR(64) NOT NULL,
  price_org_code VARCHAR(64) NOT NULL,
  accounting_month CHAR(7) NOT NULL,
  drawing_no VARCHAR(255) COLLATE utf8mb4_bin NOT NULL,
  input_snapshot_json LONGTEXT NOT NULL,
  active_scope_key CHAR(64) COLLATE utf8mb4_bin NULL,
  task_state VARCHAR(24) NOT NULL DEFAULT 'REQUESTING',
  edit_url TEXT NULL,
  difference_count INT NULL,
  current_result_id BIGINT NULL,
  consumed_result_id BIGINT NULL,
  consumption_state VARCHAR(24) NOT NULL DEFAULT 'NOT_READY',
  error_code VARCHAR(64) NULL,
  error_message VARCHAR(1000) NULL,
  row_version INT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_drawing_request (request_id),
  UNIQUE KEY uk_drawing_external_task (source_system, external_task_id),
  UNIQUE KEY uk_drawing_active_scope (active_scope_key),
  KEY idx_drawing_quote_scope (business_unit_type, oa_form_item_id, accounting_month, id),
  CONSTRAINT fk_drawing_quote_item FOREIGN KEY (oa_form_item_id) REFERENCES oa_form_item(id),
  CONSTRAINT ck_drawing_input_json CHECK (JSON_VALID(input_snapshot_json)),
  CONSTRAINT ck_drawing_month CHECK (accounting_month REGEXP '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  CONSTRAINT ck_drawing_task_state CHECK (task_state IN ('REQUESTING','WAIT_REVIEW','RECEIVED','FAILED','SUPERSEDED')),
  CONSTRAINT ck_drawing_consumption CHECK (consumption_state IN ('NOT_READY','PENDING','COMPLETE','FAILED')),
  CONSTRAINT ck_drawing_difference CHECK (difference_count IS NULL OR difference_count >= 0),
  CONSTRAINT ck_drawing_row_version CHECK (row_version >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报价产品图纸核验任务与消费进度';

CREATE TABLE IF NOT EXISTS lp_drawing_verification_result (
  id BIGINT NOT NULL AUTO_INCREMENT,
  verification_task_id BIGINT NOT NULL,
  event_type VARCHAR(24) NOT NULL,
  upstream_revision INT NOT NULL,
  raw_summary_json LONGTEXT NOT NULL,
  data_json LONGTEXT NOT NULL,
  plm_data_json LONGTEXT NOT NULL,
  plm_source_result_id BIGINT NULL,
  content_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  confirmed_at_ms BIGINT NULL,
  received_at DATETIME(6) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_drawing_result_event (verification_task_id, event_type),
  UNIQUE KEY uk_drawing_result_owner (verification_task_id, id),
  CONSTRAINT fk_drawing_result_task FOREIGN KEY (verification_task_id) REFERENCES lp_drawing_verification_task(id),
  CONSTRAINT fk_drawing_plm_source FOREIGN KEY (verification_task_id, plm_source_result_id)
    REFERENCES lp_drawing_verification_result(verification_task_id, id),
  CONSTRAINT ck_drawing_result_event CHECK (event_type IN ('INITIAL','MANUAL_CONFIRMATION')),
  CONSTRAINT ck_drawing_result_revision CHECK (upstream_revision >= 0),
  CONSTRAINT ck_drawing_summary_json CHECK (JSON_VALID(raw_summary_json)),
  CONSTRAINT ck_drawing_data_json CHECK (JSON_VALID(data_json) AND JSON_TYPE(CAST(data_json AS JSON))='OBJECT'),
  CONSTRAINT ck_drawing_plm_json CHECK (JSON_VALID(plm_data_json) AND JSON_TYPE(CAST(plm_data_json AS JSON))='ARRAY'),
  CONSTRAINT ck_drawing_plm_link CHECK (
    (event_type='INITIAL' AND plm_source_result_id IS NULL)
    OR (event_type='MANUAL_CONFIRMATION' AND plm_source_result_id IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='首次核验及人工确认的不可变原文与PLM来源';

DROP PROCEDURE IF EXISTS v338_drawing_references;
DELIMITER //
CREATE PROCEDURE v338_drawing_references()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
      WHERE constraint_schema=DATABASE() AND constraint_name='fk_drawing_current_result') THEN
    ALTER TABLE lp_drawing_verification_task
      ADD CONSTRAINT fk_drawing_current_result FOREIGN KEY (id,current_result_id)
        REFERENCES lp_drawing_verification_result(verification_task_id,id),
      ADD CONSTRAINT fk_drawing_consumed_result FOREIGN KEY (id,consumed_result_id)
        REFERENCES lp_drawing_verification_result(verification_task_id,id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_quote_bom_supplement_version' AND column_name='drawing_result_id') THEN
    ALTER TABLE lp_quote_bom_supplement_version
      ADD COLUMN drawing_result_id BIGINT NULL COMMENT 'PDF不可变结果；原有来源为空',
      ADD INDEX idx_bom_drawing_result (drawing_result_id),
      ADD CONSTRAINT fk_bom_drawing_result FOREIGN KEY (drawing_result_id) REFERENCES lp_drawing_verification_result(id);
  END IF;
END//
DELIMITER ;
CALL v338_drawing_references();
DROP PROCEDURE v338_drawing_references;

DROP TRIGGER IF EXISTS trg_drawing_result_no_update;
DROP TRIGGER IF EXISTS trg_drawing_result_no_delete;
DELIMITER //
CREATE TRIGGER trg_drawing_result_no_update BEFORE UPDATE ON lp_drawing_verification_result
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Drawing verification evidence is immutable';
END//
CREATE TRIGGER trg_drawing_result_no_delete BEFORE DELETE ON lp_drawing_verification_result
FOR EACH ROW BEGIN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Drawing verification evidence is immutable';
END//
DELIMITER ;
