-- QCBP-25：协作人工补偿独立审计，不修改任何既有业务表结构。
CREATE TABLE IF NOT EXISTS lp_quote_collaboration_admin_action (
  id BIGINT NOT NULL AUTO_INCREMENT,
  request_key VARCHAR(128) NOT NULL COMMENT '调用方幂等键',
  action_type VARCHAR(64) NOT NULL,
  target_type VARCHAR(64) NOT NULL,
  target_id BIGINT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  before_status VARCHAR(64) DEFAULT NULL,
  after_status VARCHAR(64) DEFAULT NULL,
  trace_id VARCHAR(128) DEFAULT NULL,
  oa_no VARCHAR(64) DEFAULT NULL,
  oa_form_item_id BIGINT DEFAULT NULL,
  task_no VARCHAR(64) DEFAULT NULL,
  target_version INT DEFAULT NULL,
  publish_batch_no VARCHAR(64) DEFAULT NULL,
  operator_user_id BIGINT NOT NULL,
  operator_name VARCHAR(128) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_admin_action_request (request_key),
  KEY idx_qc_admin_action_target (target_type,target_id,created_at),
  KEY idx_qc_admin_action_trace (trace_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价协作人工补偿审计';
