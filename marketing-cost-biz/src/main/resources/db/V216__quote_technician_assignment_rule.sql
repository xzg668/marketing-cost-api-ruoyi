-- QCBP-28：报价技术负责人本地匹配规则。
-- 当前由实施人员手工维护；只新增本表，不修改任何既有业务表。
CREATE TABLE IF NOT EXISTS lp_quote_technician_assignment_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  rule_code VARCHAR(64) NOT NULL COMMENT '规则唯一编码',
  rule_name VARCHAR(128) DEFAULT NULL COMMENT '规则名称',
  business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元：COMMERCIAL/HOUSEHOLD',
  process_code VARCHAR(64) DEFAULT NULL COMMENT 'OA流程编码；空值表示不限',
  source_business_division VARCHAR(128) DEFAULT NULL COMMENT '来源事业部；空值表示不限',
  applicant_department VARCHAR(128) DEFAULT NULL COMMENT '申请部门；空值表示不限',
  applicant_office VARCHAR(128) DEFAULT NULL COMMENT '申请处室；空值表示不限',
  technician_user_id BIGINT NOT NULL COMMENT '报价系统sys_user.user_id',
  technician_oa_user_id VARCHAR(64) DEFAULT NULL COMMENT '未来OA稳定用户ID预留',
  technician_job_no VARCHAR(64) DEFAULT NULL COMMENT '未来人员工号预留',
  priority INT NOT NULL DEFAULT 100 COMMENT '数字越小优先级越高',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED' COMMENT 'ENABLED/DISABLED',
  effective_from DATE DEFAULT NULL,
  effective_to DATE DEFAULT NULL,
  source_type VARCHAR(32) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/FINANCE/OA',
  source_record_id VARCHAR(128) DEFAULT NULL,
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  remark VARCHAR(500) DEFAULT NULL,
  deleted TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_qc_technician_rule_code (rule_code),
  KEY idx_qc_technician_rule_match (
    business_unit_type, status, deleted, effective_from, effective_to, priority
  ),
  KEY idx_qc_technician_rule_user (technician_user_id, status, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='报价技术负责人匹配规则';
