-- =====================================================================
-- V24: 联动价改造 —— lp_price_variable 扩展 + PART_CONTEXT seed
--   factor_type          三层模型分类：FINANCE_FACTOR/PART_CONTEXT/FORMULA_REF/CONST
--   aliases_json         中文/符号别名 JSON 数组（给 FormulaNormalizer 扫描用）
--   context_binding_json PART_CONTEXT 实体字段绑定或派生策略
-- 配套 9 条 PART_CONTEXT 内置变量 seed，不改写已存在的 FINANCE_FACTOR 主键。
-- =====================================================================

-- 1) 三列扩展（幂等：直接 ADD COLUMN / ADD KEY，迁移工具二次执行时会被忽略）
ALTER TABLE `lp_price_variable`
  ADD COLUMN `factor_type` VARCHAR(16) NOT NULL DEFAULT 'FORMULA_REF'
    COMMENT 'FINANCE_FACTOR / PART_CONTEXT / FORMULA_REF / CONST',
  ADD COLUMN `aliases_json` VARCHAR(512) DEFAULT NULL
    COMMENT '中文/符号别名 JSON 数组（例如 ["电解铜","铜"]）',
  ADD COLUMN `context_binding_json` VARCHAR(512) DEFAULT NULL
    COMMENT 'PART_CONTEXT 字段绑定或派生策略 JSON';

-- 2) factor_type 索引（排查 PART_CONTEXT/FINANCE_FACTOR 分类查询常用）
ALTER TABLE `lp_price_variable`
  ADD KEY `idx_factor_type` (`factor_type`);

-- 3) 9 条 PART_CONTEXT 内置变量 seed（INSERT IGNORE 保证幂等；已存在则走下方 UPDATE）
INSERT IGNORE INTO `lp_price_variable`
  (`variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`,
   `scope`, `status`, `tax_mode`, `factor_type`, `aliases_json`, `context_binding_json`,
   `created_at`, `updated_at`)
VALUES
  -- 下料重量：部品实体字段 blank_weight，单位 g→kg 需 ×0.001
  ('blank_weight', '下料重量', 'LINKED_ITEM', 'lp_price_linked_item', 'blank_weight',
   'GLOBAL', 'active', 'NONE', 'PART_CONTEXT',
   JSON_ARRAY('下料重量','下料重'),
   -- ENTITY 绑定契约：entity=Java 实体 bean 名（linkedItem），field=camelCase getter 后缀
   -- FactorVariableRegistryImpl.resolveEntityBinding 用反射 getBlankWeight() 取值
   JSON_OBJECT('source','ENTITY','entity','linkedItem','field','blankWeight','unitScale',0.001),
   NOW(), NOW()),
  -- 产品净重：同上，g→kg
  ('net_weight', '产品净重', 'LINKED_ITEM', 'lp_price_linked_item', 'net_weight',
   'GLOBAL', 'active', 'NONE', 'PART_CONTEXT',
   JSON_ARRAY('产品净重','净重'),
   JSON_OBJECT('source','ENTITY','entity','linkedItem','field','netWeight','unitScale',0.001),
   NOW(), NOW()),
  -- 加工费：部品实体字段 process_fee，已含税
  ('process_fee', '加工费', 'LINKED_ITEM', 'lp_price_linked_item', 'process_fee',
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('加工费'),
   JSON_OBJECT('source','ENTITY','entity','linkedItem','field','processFee'),
   NOW(), NOW()),
  -- 含税加工费：与加工费同字段，仅标签不同
  ('process_fee_incl', '含税加工费', 'LINKED_ITEM', 'lp_price_linked_item', 'process_fee',
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('含税加工费'),
   JSON_OBJECT('source','ENTITY','entity','linkedItem','field','processFee'),
   NOW(), NOW()),
  -- 代理费
  ('agent_fee', '代理费', 'LINKED_ITEM', 'lp_price_linked_item', 'agent_fee',
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('代理费'),
   JSON_OBJECT('source','ENTITY','entity','linkedItem','field','agentFee'),
   NOW(), NOW()),
  -- 材料含税价格：派生 —— 查主材料 code 后走 FINANCE_FACTOR
  ('material_price_incl', '材料含税价格', 'DERIVED', NULL, NULL,
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('材料含税价格','材料价格'),
   JSON_OBJECT('source','DERIVED','strategy','MAIN_MATERIAL_FINANCE'),
   NOW(), NOW()),
  -- 废料含税价格：派生 —— 走 lp_material_scrap_ref 映射
  ('scrap_price_incl', '废料含税价格', 'DERIVED', NULL, NULL,
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('废料含税价格','废料价格'),
   JSON_OBJECT('source','DERIVED','strategy','SCRAP_REF'),
   NOW(), NOW()),
  -- 铜沫价格：派生 —— 固定公式 [Cu]*0.59+[Zn]*0.41
  ('copper_scrap_price', '铜沫价格', 'DERIVED', NULL, NULL,
   'GLOBAL', 'active', 'INCL', 'PART_CONTEXT',
   JSON_ARRAY('铜沫价格'),
   JSON_OBJECT('source','DERIVED','strategy','FORMULA_REF','formulaRef','[Cu]*0.59+[Zn]*0.41'),
   NOW(), NOW()),
  -- 美国柜装黄铜价格：派生 —— 指向 FINANCE_FACTOR factor_code='美国柜装黄铜'
  ('us_yellow_copper_price', '美国柜装黄铜价格', 'DERIVED', NULL, NULL,
   'GLOBAL', 'active', 'EXCL', 'PART_CONTEXT',
   JSON_ARRAY('美国柜装黄铜价格'),
   JSON_OBJECT('source','DERIVED','strategy','FINANCE_FACTOR','factorCode','美国柜装黄铜'),
   NOW(), NOW());

-- 4) 把 marketing_cost.sql 老 seed（blank_weight 等）回填 factor_type/别名/绑定
--    INSERT IGNORE 对已存在行无效果，所以用 UPDATE 兜底；避免双份数据分叉。
UPDATE `lp_price_variable` SET
  factor_type = 'PART_CONTEXT',
  aliases_json = JSON_ARRAY('下料重量','下料重'),
  context_binding_json = JSON_OBJECT('source','ENTITY','entity','linkedItem','field','blankWeight','unitScale',0.001)
  WHERE variable_code = 'blank_weight';

UPDATE `lp_price_variable` SET
  factor_type = 'PART_CONTEXT',
  aliases_json = JSON_ARRAY('产品净重','净重'),
  context_binding_json = JSON_OBJECT('source','ENTITY','entity','linkedItem','field','netWeight','unitScale',0.001)
  WHERE variable_code = 'net_weight';

UPDATE `lp_price_variable` SET
  factor_type = 'PART_CONTEXT',
  aliases_json = JSON_ARRAY('加工费'),
  context_binding_json = JSON_OBJECT('source','ENTITY','entity','linkedItem','field','processFee')
  WHERE variable_code = 'process_fee';

UPDATE `lp_price_variable` SET
  factor_type = 'PART_CONTEXT',
  aliases_json = JSON_ARRAY('代理费'),
  context_binding_json = JSON_OBJECT('source','ENTITY','entity','linkedItem','field','agentFee')
  WHERE variable_code = 'agent_fee';

-- 5) 给现有 FINANCE_FACTOR 样变量补 factor_type + 别名（影响因素 10 简称列抽取）
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('电解铜','铜','1#Cu') WHERE variable_code = 'Cu';
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('电解锌','锌','1#Zn') WHERE variable_code = 'Zn';
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('电解铝','铝','A00') WHERE variable_code = 'Al';
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('1#锡','锡') WHERE variable_code = 'Sn';
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('其他材料') WHERE variable_code = 'Cn';
UPDATE `lp_price_variable` SET factor_type = 'FINANCE_FACTOR',
  aliases_json = JSON_ARRAY('美国柜装黄铜') WHERE variable_code = 'us_brass_price';

-- 6) vat_rate/Cu_excl 等 V13 seed 的 factor_type 归位
UPDATE `lp_price_variable` SET factor_type = 'CONST' WHERE variable_code = 'vat_rate';
UPDATE `lp_price_variable` SET factor_type = 'FORMULA_REF' WHERE variable_code = 'Cu_excl';
