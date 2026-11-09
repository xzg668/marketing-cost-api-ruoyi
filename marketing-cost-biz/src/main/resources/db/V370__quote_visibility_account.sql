-- 报价员范围独立配置，不改变COMMERCIAL/HOUSEHOLD及任何报价业务数据。
CREATE TABLE lp_quote_visibility_account (
  user_id BIGINT NOT NULL PRIMARY KEY COMMENT '系统账号ID',
  quote_scope VARCHAR(16) NOT NULL COMMENT 'PLATE板换报价员；COMMERCIAL商用报价员；历史混合单双方可见',
  created_by VARCHAR(64) NULL,
  updated_by VARCHAR(64) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT ck_quote_visibility_scope CHECK (quote_scope IN ('PLATE','COMMERCIAL'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='报价员可见范围配置';
