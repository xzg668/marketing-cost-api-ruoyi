-- =====================================================================
-- V15: lp_raw_material_breakdown —— Task #8 (原材料拆解)
--   父件 → 子件多行拆解；用于"原材料拆解"取价桶 (RAW_BREAKDOWN)
--   父件单价 = Σ ( 子件单价 × 子件用量 )
-- =====================================================================

CREATE TABLE IF NOT EXISTS `lp_raw_material_breakdown` (
  `id`                 BIGINT NOT NULL AUTO_INCREMENT,
  `parent_code`        VARCHAR(64) NOT NULL COMMENT '父件物料号（被拆解的成品/半成品）',
  `parent_name`        VARCHAR(128) DEFAULT NULL,
  `child_code`         VARCHAR(64) NOT NULL COMMENT '子件物料号（拆解出的下层物料）',
  `child_name`         VARCHAR(128) DEFAULT NULL,
  `subject`            VARCHAR(64) DEFAULT NULL COMMENT '科目分类（材料费/加工费/水电...）',
  `quantity`           DECIMAL(20, 6) NOT NULL DEFAULT 0 COMMENT '子件用量',
  `unit`               VARCHAR(32) DEFAULT NULL COMMENT '用量单位',
  `period`             VARCHAR(7) NOT NULL DEFAULT '' COMMENT '价格期间 yyyy-MM',
  `seq`                INT DEFAULT 0 COMMENT '展示顺序',
  `remark`             VARCHAR(255) DEFAULT NULL,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_parent_period` (`parent_code`, `period`),
  KEY `idx_child_code` (`child_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='原材料拆解关系 (Task #8)';
