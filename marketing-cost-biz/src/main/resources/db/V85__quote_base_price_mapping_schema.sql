-- =====================================================================
-- V85: 报价单基价覆盖影响因素变量 - 映射数据模型
--
-- 目标：
--   1) lp_quote_base_price_mapping_rule 维护“哪些影响因素文本可识别成报价单基价字段”。
--   2) lp_factor_quote_base_mapping 保存“某个影响因素身份最终绑定到哪个报价单基价字段”。
--   3) 当前阶段只承接 Cu/Zn/Al 这类 OA 表头锁价覆盖，后续字段扩展必须先确认单位和公式口径。
-- =====================================================================

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS lp_quote_base_price_mapping_rule (
  id BIGINT NOT NULL AUTO_INCREMENT,
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元；空串表示全业务单元默认规则',
  quote_field_code VARCHAR(64) NOT NULL COMMENT 'OA 报价单基价字段编码，如 copper_price/zinc_price/aluminum_price',
  quote_field_name VARCHAR(64) NOT NULL COMMENT '页面展示名，如铜基价/锌基价/铝基价',
  variable_code VARCHAR(64) NOT NULL COMMENT '兼容老公式变量编码，如 Cu/Zn/Al',
  match_keywords_json TEXT NOT NULL COMMENT '关键词 JSON 数组，用于从影响因素名称/简称中识别公共基价',
  match_mode VARCHAR(32) NOT NULL DEFAULT 'ANY_KEYWORD' COMMENT '匹配模式：ANY_KEYWORD 表示命中任一关键词即可',
  priority INT NOT NULL DEFAULT 100 COMMENT '规则优先级，数字越小越优先',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
  remark VARCHAR(500) DEFAULT NULL COMMENT '规则说明',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_base_mapping_rule (
    business_unit_type, quote_field_code, variable_code, deleted
  ),
  KEY idx_quote_base_rule_field (quote_field_code, enabled, deleted),
  KEY idx_quote_base_rule_bu_priority (business_unit_type, enabled, priority, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='报价单基价映射规则：影响因素文本到 OA 表头基价字段';

CREATE TABLE IF NOT EXISTS lp_factor_quote_base_mapping (
  id BIGINT NOT NULL AUTO_INCREMENT,
  factor_identity_id BIGINT NOT NULL COMMENT 'lp_factor_identity.id',
  rule_id BIGINT DEFAULT NULL COMMENT '命中的 lp_quote_base_price_mapping_rule.id；人工绑定可为空',
  quote_field_code VARCHAR(64) NOT NULL COMMENT 'OA 报价单基价字段编码',
  quote_field_name VARCHAR(64) NOT NULL COMMENT 'OA 报价单基价字段展示名',
  variable_code VARCHAR(64) NOT NULL COMMENT '兼容老公式变量编码，如 Cu/Zn/Al',
  matched_keyword VARCHAR(128) DEFAULT NULL COMMENT '自动识别时命中的关键词',
  match_source VARCHAR(32) NOT NULL DEFAULT 'AUTO' COMMENT 'AUTO=规则自动识别，MANUAL=人工调整',
  confidence VARCHAR(32) NOT NULL DEFAULT 'HIGH' COMMENT '识别置信度：HIGH/MEDIUM/LOW',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
  created_by VARCHAR(64) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by VARCHAR(64) DEFAULT NULL,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_factor_quote_base_mapping (factor_identity_id, quote_field_code, deleted),
  KEY idx_factor_quote_base_field (quote_field_code, enabled, deleted),
  KEY idx_factor_quote_base_rule (rule_id),
  KEY idx_factor_quote_base_source (match_source, confidence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='影响因素到报价单基价字段的识别结果';
