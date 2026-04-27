-- V36：行局部占位符配置表 —— 把 __material / __scrap 之类的"行局部宏"从 Java 硬编码
-- 挪到 DB 管理，支持运维/财务自助扩展（新增 __packaging / __coating 等）无需重新发版。
--
-- 背景：历史上 FactorVariableRegistryImpl.ROW_LOCAL_TOKEN_NAMES 和
-- FormulaDisplayRenderer.ROW_LOCAL_DISPLAY_NAMES 都是 static final，要加占位符就得改
-- Java 源码 + 重新构建部署。本迁移引入 lp_row_local_placeholder 统一承载：
--   * code          —— 占位符，如 __material（public API，公式里直接用）
--   * display_name  —— 中文回显名，Renderer 用
--   * token_names_json —— binding.token_name 里的可接受字面值清单，Registry 用
--
-- 迁移幂等守护：用 INFORMATION_SCHEMA 判断表是否存在再建；即使误跑第二次也不报错。

SET @table_exists := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lp_row_local_placeholder'
);

SET @create_sql := IF(@table_exists = 0, '
CREATE TABLE lp_row_local_placeholder (
  id              BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT "主键",
  code            VARCHAR(32)  NOT NULL COMMENT "占位符 code，如 __material；公式里直接用 [code]",
  display_name    VARCHAR(64)  NOT NULL COMMENT "中文公式回显名，如 材料含税价格",
  token_names_json JSON        NOT NULL COMMENT "binding.token_name 候选字面值清单，如 [\\"材料含税价格\\",\\"材料价格\\"]",
  description     VARCHAR(255)          COMMENT "占位符的业务含义描述",
  sort_order      INT          NOT NULL DEFAULT 0 COMMENT "显示排序",
  status          VARCHAR(16)  NOT NULL DEFAULT "active" COMMENT "active / inactive",
  created_by      VARCHAR(64),
  created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_by      VARCHAR(64),
  updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  deleted         TINYINT(1)   NOT NULL DEFAULT 0 COMMENT "MyBatis Plus 软删：0=未删 / 1=已删",
  UNIQUE KEY uk_code_deleted (code, deleted),
  KEY idx_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT="行局部占位符配置 —— __material / __scrap 之类"
', 'SELECT 1');

PREPARE stmt FROM @create_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Seed 既有两个占位符；只有表刚新建且无任何行时插入，避免升级老环境覆盖已有配置。
INSERT INTO lp_row_local_placeholder
  (code, display_name, token_names_json, description, sort_order, status, created_by)
SELECT '__material', '材料含税价格',
       JSON_ARRAY('材料含税价格', '材料价格'),
       '部品主材料单价占位符（含税）；实际 factor_code 来自 lp_price_variable_binding',
       10, 'active', 'migration'
WHERE NOT EXISTS (
  SELECT 1 FROM lp_row_local_placeholder WHERE code = '__material' AND deleted = 0
);

INSERT INTO lp_row_local_placeholder
  (code, display_name, token_names_json, description, sort_order, status, created_by)
SELECT '__scrap', '废料含税价格',
       JSON_ARRAY('废料含税价格', '废料价格'),
       '部品废料单价占位符（含税）；实际 factor_code 来自 lp_price_variable_binding',
       20, 'active', 'migration'
WHERE NOT EXISTS (
  SELECT 1 FROM lp_row_local_placeholder WHERE code = '__scrap' AND deleted = 0
);
