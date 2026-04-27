-- =====================================================================
-- V14: lp_make_part_spec —— Task #8 (制造件工艺规格)
--   存储制造件取价所需的"几何 + 原材料 + 回收 + 加工费 + 公式绑定"
--   一个 material_code + period 组合一行；MakePartResolver 按此推算成本
-- =====================================================================

CREATE TABLE IF NOT EXISTS `lp_make_part_spec` (
  `id`                 BIGINT NOT NULL AUTO_INCREMENT,
  `material_code`      VARCHAR(64) NOT NULL COMMENT '制造件物料号',
  `material_name`      VARCHAR(128) DEFAULT NULL COMMENT '制造件名称',
  `drawing_no`         VARCHAR(128) DEFAULT NULL COMMENT '图号',
  `period`             VARCHAR(7)  NOT NULL DEFAULT '' COMMENT '价格期间 yyyy-MM',
  `blank_weight`       DECIMAL(20, 6) DEFAULT NULL COMMENT '下料重量(克)',
  `net_weight`         DECIMAL(20, 6) DEFAULT NULL COMMENT '净重(克)',
  `scrap_rate`         DECIMAL(10, 6) DEFAULT NULL COMMENT '废品率(0~1)',
  `raw_material_code`  VARCHAR(64) DEFAULT NULL COMMENT '原材料/上游件代码（递归取价用）',
  `raw_material_spec`  VARCHAR(255) DEFAULT NULL COMMENT '原材料规格描述（人工录入）',
  `raw_unit_price`     DECIMAL(20, 8) DEFAULT NULL COMMENT '原材料单价(元/千克)，为空时用 raw_material_code 递归取价',
  `recycle_code`       VARCHAR(32) DEFAULT NULL COMMENT '回收等级代码 (A/B/C/D)',
  `recycle_unit_price` DECIMAL(20, 8) DEFAULT NULL COMMENT '回收料单价(元/千克)',
  `recycle_ratio`      DECIMAL(10, 6) DEFAULT 1.000000 COMMENT '回收抵减比例(默认1)',
  `process_fee`        DECIMAL(20, 6) DEFAULT 0 COMMENT '加工费(元/件)',
  `outsource_fee`      DECIMAL(20, 6) DEFAULT 0 COMMENT '委外加工费(元/件)',
  `formula_id`         BIGINT DEFAULT NULL COMMENT '绑定 lp_formula_instance.id；为空走默认骨架',
  `effective_from`     DATE DEFAULT NULL COMMENT '生效起',
  `effective_to`       DATE DEFAULT NULL COMMENT '生效止',
  `remark`             VARCHAR(255) DEFAULT NULL,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_period` (`material_code`, `period`),
  KEY `idx_raw_material_code` (`raw_material_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='制造件工艺规格 + 取价参数 (Task #8)';
