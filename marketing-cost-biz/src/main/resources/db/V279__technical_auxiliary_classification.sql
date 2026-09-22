-- 财务归类与技术审批快照分开；不同技术版本不得继承旧归类。
CREATE TABLE IF NOT EXISTS lp_quote_tech_aux_classification (
  id BIGINT NOT NULL AUTO_INCREMENT,
  technical_version_id BIGINT NOT NULL,
  detail_id BIGINT NOT NULL,
  content_fingerprint CHAR(64) NOT NULL,
  subject_code VARCHAR(64) NOT NULL,
  subject_name VARCHAR(255) NOT NULL,
  classified_by BIGINT NOT NULL,
  classified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_tech_aux_classification (technical_version_id, detail_id),
  CONSTRAINT fk_tech_aux_classification_version FOREIGN KEY (technical_version_id) REFERENCES lp_quote_tech_data_version(id),
  CONSTRAINT fk_tech_aux_classification_detail FOREIGN KEY (detail_id) REFERENCES lp_quote_tech_aux_item(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
