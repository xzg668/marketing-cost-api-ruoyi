-- =====================================================================
-- V12：公式引擎落表 —— 模板 + 实例 两层
--   背景：金标 Excel 见机表3 包含 5 类典型公式（合金抵减/单金属/焊料/材料价+加工/计划价上浮），
--         系数与配料随产品而变，但骨架可枚举。
--   设计：
--     lp_formula_template  —— 内置模板骨架（5 个），不允许业务用户编辑
--     lp_formula_instance  —— 业务实例（绑定模板 + JSON 参数 + 适用 material_code 列表）
--   兼容：本期只读模板，不开放业务编辑接口；先用 seed 灌入 5 条基线模板。
-- =====================================================================

CREATE TABLE IF NOT EXISTS `lp_formula_template` (
  `id`             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_code`  VARCHAR(64)  NOT NULL COMMENT '模板编码：ALLOY_SCRAP/SINGLE_METAL/WELD_ALLOY/MATERIAL_UNIT_PLUS_FEE/PLAN_UPLIFT',
  `template_name`  VARCHAR(64)  NOT NULL COMMENT '模板名称（中文）',
  `engine_type`    VARCHAR(16)  NOT NULL DEFAULT 'TEMPLATE' COMMENT '引擎类型：TEMPLATE（本期）/DSL/SCRIPT（后续）',
  `description`    VARCHAR(255) DEFAULT NULL COMMENT '模板说明：算法骨架与适用场景',
  `schema_json`    JSON         NOT NULL COMMENT '入参 schema：{"fields":[{"name":"netWeight","type":"decimal","required":true},...]}',
  `enabled`        TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
  `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_template_code` (`template_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公式引擎-模板表';

CREATE TABLE IF NOT EXISTS `lp_formula_instance` (
  `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
  `template_code`   VARCHAR(64)  NOT NULL COMMENT '关联 lp_formula_template.template_code',
  `instance_name`   VARCHAR(128) NOT NULL COMMENT '实例名（业务可读，如"端盖-合金抵减-2026Q2"）',
  `material_code`   VARCHAR(64)  DEFAULT NULL COMMENT '绑定的物料编码；NULL 表示通用',
  `period`          VARCHAR(7)   DEFAULT NULL COMMENT '账期 yyyy-MM；NULL 表示长期',
  `params_json`     JSON         NOT NULL COMMENT '实例参数 JSON：覆盖模板 schema 中的字段（如 netWeight=0.5）',
  `effective_from`  DATE         DEFAULT NULL COMMENT '生效起（含）',
  `effective_to`    DATE         DEFAULT NULL COMMENT '生效止（含）',
  `enabled`         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=停用',
  `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_material_period` (`material_code`, `period`),
  KEY `idx_template_code` (`template_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='公式引擎-实例表';

-- 内置 5 个模板 seed（template_code 唯一约束保证幂等）
INSERT INTO `lp_formula_template` (`template_code`, `template_name`, `engine_type`, `description`, `schema_json`)
VALUES
  ('ALLOY_SCRAP', '合金+废料抵减',  'TEMPLATE',
   '总价 = 合金材料价 × 下料重 - (下料重 - 净重) × 废料价 × 抵减比例 + 加工费',
   JSON_OBJECT('fields', JSON_ARRAY(
     JSON_OBJECT('name','alloyPrice','type','decimal','required',true,'note','合金材料价'),
     JSON_OBJECT('name','blankWeight','type','decimal','required',true,'note','下料重(g)'),
     JSON_OBJECT('name','netWeight','type','decimal','required',true,'note','净重(g)'),
     JSON_OBJECT('name','scrapPrice','type','decimal','required',true,'note','废料价'),
     JSON_OBJECT('name','scrapRatio','type','decimal','required',false,'default',1.0,'note','抵减比例（默认1）'),
     JSON_OBJECT('name','processFee','type','decimal','required',false,'default',0,'note','加工费')))),
  ('SINGLE_METAL', '单金属+加工费', 'TEMPLATE',
   '总价 = 材料价 × 净重 + 加工费',
   JSON_OBJECT('fields', JSON_ARRAY(
     JSON_OBJECT('name','materialPrice','type','decimal','required',true,'note','材料单价'),
     JSON_OBJECT('name','netWeight','type','decimal','required',true,'note','净重(g)'),
     JSON_OBJECT('name','processFee','type','decimal','required',false,'default',0,'note','加工费')))),
  ('WELD_ALLOY', '焊料-多金属配比', 'TEMPLATE',
   '总价 = sum(metalPrice_i × ratio_i) × 净重 + 加工费',
   JSON_OBJECT('fields', JSON_ARRAY(
     JSON_OBJECT('name','metals','type','array','required',true,
       'note','配比数组：[{price,ratio}]，ratio 总和=1'),
     JSON_OBJECT('name','netWeight','type','decimal','required',true,'note','净重(g)'),
     JSON_OBJECT('name','processFee','type','decimal','required',false,'default',0,'note','加工费')))),
  ('MATERIAL_UNIT_PLUS_FEE', '材料价+加工费(套管/连杆)', 'TEMPLATE',
   '总价 = 材料价 × 下料重 + 加工费',
   JSON_OBJECT('fields', JSON_ARRAY(
     JSON_OBJECT('name','materialPrice','type','decimal','required',true,'note','材料单价'),
     JSON_OBJECT('name','blankWeight','type','decimal','required',true,'note','下料重(g)'),
     JSON_OBJECT('name','processFee','type','decimal','required',false,'default',0,'note','加工费')))),
  ('PLAN_UPLIFT', '计划价上浮(家用结算)', 'TEMPLATE',
   '总价 = 计划价 × (1 + 上浮比例)',
   JSON_OBJECT('fields', JSON_ARRAY(
     JSON_OBJECT('name','planPrice','type','decimal','required',true,'note','计划价'),
     JSON_OBJECT('name','upliftRatio','type','decimal','required',true,'note','上浮比例（如 0.05）'))))
ON DUPLICATE KEY UPDATE
  template_name = VALUES(template_name),
  description   = VALUES(description),
  schema_json   = VALUES(schema_json);
