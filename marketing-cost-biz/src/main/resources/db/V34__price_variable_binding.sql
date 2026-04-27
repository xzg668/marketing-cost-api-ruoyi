-- =====================================================================
-- V34: 行局部变量绑定（lp_price_variable_binding）+ 影响因素补齐 + 权限 seed
--
-- 背景：
--   联动价-部品6 sheet 里 R2/R4/R7/R10 四行的公式用了通用 token：
--     「材料含税价格」「材料价格」「废料含税价格」「废料价格」
--   四行字面相同但指向不同的影响因素——这种"行局部"的 token→因素 映射
--   由供管部提供，系统需要一张独立表承载。
--
--   另：原材料(联动+固定-7) sheet 的公式用到 Ag/In/Pcu/Mn 四个
--   FINANCE 变量，当前 lp_price_variable 里未登记，calc 会炸。
--
-- 做法：
--   1) 建表 lp_price_variable_binding
--      - (linked_item_id, token_name, effective_date, deleted) 唯一
--      - source ENUM 区分 EXCEL_INFERRED / SUPPLY_CONFIRMED / MANUAL
--      - effective_date / expiry_date 按月版本化
--      - remark 兜底供管部给的附加字段
--   2) 补齐 lp_price_variable：Ag / In / Pcu / Mn 四个 FINANCE 变量
--      + resolver_kind / resolver_params（Plan B 统一模型）
--      + aliases_json（公式里可能写中文"电解银"/"锰"等）
--   3) Sn 修名：variable_name 从"不锈钢基价"→"电解锡"（V24 误命名）
--   4) seed 8 条 binding（EXCEL_INFERRED，推断自 Excel 11 行公式语义）
--      用 INSERT ... SELECT 按 material_code 匹配 linked_item_id，
--      避免硬编码 id；若 linked_item 尚未导入，seed 自然跳过（安全）
--   5) 权限点 seed：price:linked:binding:admin / :view
--      关联 admin(1) / bu_director(10) / bu_staff(11) 三角色
--
-- 重放指令（必须 utf8mb4）：
--   docker exec -i new_mysql mysql --default-character-set=utf8mb4 \
--     -uroot -p<pw> marketing_cost \
--     < V34__price_variable_binding.sql
--
-- 幂等：重跑安全（INFORMATION_SCHEMA 守护 + INSERT IGNORE + UPDATE WHERE）
-- =====================================================================

SET NAMES utf8mb4;

-- ---------------------------------------------------------------
-- 1) 建表 lp_price_variable_binding（幂等）
-- ---------------------------------------------------------------
SET @has_binding_table := (
  SELECT COUNT(*) FROM information_schema.TABLES
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME   = 'lp_price_variable_binding');
SET @sql := IF(@has_binding_table = 0,
  'CREATE TABLE `lp_price_variable_binding` (
     `id`             BIGINT       NOT NULL AUTO_INCREMENT,
     `linked_item_id` BIGINT       NOT NULL                COMMENT ''关联 lp_price_linked_item.id'',
     `token_name`     VARCHAR(32)  NOT NULL                COMMENT ''B 组 token：材料含税价格/废料含税价格/材料价格/废料价格'',
     `factor_code`    VARCHAR(64)  NULL                    COMMENT ''指向 lp_price_variable.variable_code'',
     `price_source`   VARCHAR(16)  NULL                    COMMENT ''平均价/出厂价/招标价/采购价/现货价/月均价'',
     `bu_scoped`      TINYINT      NOT NULL DEFAULT 1      COMMENT ''是否按 BU 隔离'',
     `effective_date` DATE         NOT NULL                COMMENT ''生效日，按月切片'',
     `expiry_date`    DATE         NULL                    COMMENT ''null=当前版本；修改时旧行设 new.effective_date - 1'',
     `source`         VARCHAR(16)  NOT NULL                COMMENT ''EXCEL_INFERRED/SUPPLY_CONFIRMED/MANUAL'',
     `confirmed_by`   VARCHAR(32)  NULL                    COMMENT ''确认人（SUPPLY_CONFIRMED 时填）'',
     `confirmed_at`   TIMESTAMP    NULL                    COMMENT ''确认时间'',
     `remark`         VARCHAR(256) NULL                    COMMENT ''备注：供管部批次号/人工修正原因等'',
     `created_by`     VARCHAR(32)  NULL,
     `created_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
     `updated_by`     VARCHAR(32)  NULL,
     `updated_at`     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
     `deleted`        TINYINT      NOT NULL DEFAULT 0      COMMENT ''软删除：0=未删 / 1=已删'',
     PRIMARY KEY (`id`),
     UNIQUE KEY `uk_item_token_effective` (`linked_item_id`, `token_name`, `effective_date`, `deleted`),
     KEY `idx_source` (`source`),
     KEY `idx_factor` (`factor_code`),
     KEY `idx_item`   (`linked_item_id`)
   ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
     COMMENT=''联动价行局部变量绑定：把公式里的 B 组 token 映射到影响因素''',
  'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- ---------------------------------------------------------------
-- 2) 补齐 FINANCE 变量：Ag / In / Pcu / Mn
-- ---------------------------------------------------------------
INSERT IGNORE INTO `lp_price_variable`
  (`variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`,
   `scope`, `status`, `tax_mode`, `factor_type`, `aliases_json`,
   `resolver_kind`, `resolver_params`,
   `created_at`, `updated_at`)
VALUES
  ('Ag', '电解银', 'FACTOR', 'lp_finance_base_price', 'price',
   'BASE_PRICE', 'active', 'INCL', 'FINANCE_FACTOR',
   JSON_ARRAY('电解银', '银', '1#Ag'),
   'FINANCE',
   JSON_OBJECT('factorCode', 'Ag', 'priceSource', '平均价', 'buScoped', TRUE),
   NOW(), NOW()),
  ('In', '精铟', 'FACTOR', 'lp_finance_base_price', 'price',
   'BASE_PRICE', 'active', 'INCL', 'FINANCE_FACTOR',
   JSON_ARRAY('精铟', '铟'),
   'FINANCE',
   JSON_OBJECT('factorCode', 'In', 'priceSource', '平均价', 'buScoped', TRUE),
   NOW(), NOW()),
  ('Pcu', '磷铜合金', 'FACTOR', 'lp_finance_base_price', 'price',
   'BASE_PRICE', 'active', 'INCL', 'FINANCE_FACTOR',
   JSON_ARRAY('磷铜合金', '磷铜'),
   'FINANCE',
   JSON_OBJECT('factorCode', 'Pcu', 'priceSource', '平均价', 'buScoped', TRUE),
   NOW(), NOW()),
  ('Mn', '电解锰', 'FACTOR', 'lp_finance_base_price', 'price',
   'BASE_PRICE', 'active', 'INCL', 'FINANCE_FACTOR',
   JSON_ARRAY('电解锰', '锰', '1#Mn'),
   'FINANCE',
   JSON_OBJECT('factorCode', 'Mn', 'priceSource', '平均价', 'buScoped', TRUE),
   NOW(), NOW());

-- 2b) 已存在行兜底更新（防止之前手工插过但字段不全）
UPDATE `lp_price_variable` SET
    factor_type     = 'FINANCE_FACTOR',
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT('factorCode', 'Ag', 'priceSource', '平均价', 'buScoped', TRUE),
    aliases_json    = JSON_ARRAY('电解银', '银', '1#Ag')
  WHERE variable_code = 'Ag';

UPDATE `lp_price_variable` SET
    factor_type     = 'FINANCE_FACTOR',
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT('factorCode', 'In', 'priceSource', '平均价', 'buScoped', TRUE),
    aliases_json    = JSON_ARRAY('精铟', '铟')
  WHERE variable_code = 'In';

UPDATE `lp_price_variable` SET
    factor_type     = 'FINANCE_FACTOR',
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT('factorCode', 'Pcu', 'priceSource', '平均价', 'buScoped', TRUE),
    aliases_json    = JSON_ARRAY('磷铜合金', '磷铜')
  WHERE variable_code = 'Pcu';

UPDATE `lp_price_variable` SET
    factor_type     = 'FINANCE_FACTOR',
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT('factorCode', 'Mn', 'priceSource', '平均价', 'buScoped', TRUE),
    aliases_json    = JSON_ARRAY('电解锰', '锰', '1#Mn')
  WHERE variable_code = 'Mn';

-- ---------------------------------------------------------------
-- 3) Sn 改名（V24 误写"不锈钢基价"，实际 Excel 里 1#Sn 是电解锡）
--    同时补 resolver_params（V31 留 NULL，现配齐）
-- ---------------------------------------------------------------
UPDATE `lp_price_variable` SET
    variable_name   = '电解锡',
    resolver_kind   = 'FINANCE',
    resolver_params = JSON_OBJECT('factorCode', 'Sn', 'priceSource', '平均价', 'buScoped', TRUE),
    aliases_json    = JSON_ARRAY('电解锡', '锡', '1#Sn')
  WHERE variable_code = 'Sn';

-- ---------------------------------------------------------------
-- 4) seed 8 条 EXCEL_INFERRED binding
--    用 INSERT ... SELECT 按 material_code 匹配 linked_item_id；
--    若 linked_item 表尚未导入对应物料，则自然跳过（0 行），安全。
--
--    推断依据（详见 variable-binding-design-2026-04-21.md §3.5）：
--      R2 弹簧片 203259729  材料=SUS304/2Bδ0.7  废料=废不锈钢SUS301
--      R4 支架   203259786  材料=钢板DC01 δ=1.2  废料=废铁混合料
--      R7 套管   203240231  材料=Cu              废料=copper_scrap_price (DERIVED)
--      R10 连杆  203250307  材料=Cu              废料=copper_scrap_price (DERIVED)
-- ---------------------------------------------------------------
-- 辅助宏：统一 effective_date = 2026-01-01（第一轮 seed，后续供管部覆盖）
-- INSERT IGNORE 保证幂等（UNIQUE key 冲突自动跳过）

-- R2 弹簧片
INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '材料含税价格', 'SUS304/2Bδ0.7', '出厂价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 弹簧片 SHF-G20 系列常用 0.7mm SUS304',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203259729';

INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '废料含税价格', '废不锈钢SUS301', '招标价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 弹簧片通常为 SUS301 硬态',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203259729';

-- R4 支架
INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '材料含税价格', '钢板DC01 δ=1.2', '采购价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 支架 SHF-P20 系列冷轧钢板',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203259786';

INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '废料含税价格', '废铁混合料', '招标价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 冷轧钢板废料',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203259786';

-- R7 套管
INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '材料价格', 'Cu', '平均价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 套管常用黄铜',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203240231';

INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '废料价格', 'copper_scrap_price', NULL, 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：铜沫公式 (Cu*0.59+Zn*0.41)*0.915，待供管部确认是否新建废铜条目',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203240231';

-- R10 连杆
INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '材料含税价格', 'Cu', '平均价', 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：推断自 Excel 连杆常用铜',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203250307';

INSERT IGNORE INTO `lp_price_variable_binding`
  (linked_item_id, token_name, factor_code, price_source, bu_scoped,
   effective_date, expiry_date, source, remark, created_by, created_at, updated_at, deleted)
SELECT id, '废料含税价格', 'copper_scrap_price', NULL, 1,
       '2026-01-01', NULL, 'EXCEL_INFERRED',
       'V34 首批 seed：铜沫公式同 R7',
       'system', NOW(), NOW(), 0
  FROM `lp_price_linked_item`
 WHERE material_code = '203250307';

-- ---------------------------------------------------------------
-- 5) 权限点 seed：price:linked:binding:admin / :view
--    挂 parent_id=401（联动价目录），紧跟 V33 的 40154/40155
-- ---------------------------------------------------------------
INSERT IGNORE INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, menu_type, visible, status,
   perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40156, '变量绑定查看', 401, 16, '', NULL, 'F', '0', '0',
   'price:linked:binding:view', '#', 'admin', NOW(), '', NOW(),
   '联动价维护页 变量绑定 tab 查看 GET /api/v1/price-linked/bindings*'),
  (40157, '变量绑定运维', 401, 17, '', NULL, 'F', '0', '0',
   'price:linked:binding:admin', '#', 'admin', NOW(), '', NOW(),
   '联动价维护页 变量绑定 tab 新增/修改/删除/CSV 导入');

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40156), (1, 40157),
  (10, 40156), (10, 40157),
  (11, 40156), (11, 40157);

-- ---------------------------------------------------------------
-- 6) 人工校验 SQL（拷到客户端运行，不改数据）
-- ---------------------------------------------------------------
-- (a) 确认表已建
-- SELECT TABLE_NAME FROM information_schema.TABLES
--  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_price_variable_binding';
--
-- (b) 索引齐全
-- SHOW INDEX FROM lp_price_variable_binding;
--
-- (c) 4 个新 FINANCE 变量
-- SELECT variable_code, variable_name, factor_type, resolver_kind,
--        CAST(resolver_params AS CHAR CHARACTER SET utf8mb4) AS params,
--        CAST(aliases_json AS CHAR CHARACTER SET utf8mb4) AS aliases
--   FROM lp_price_variable
--  WHERE variable_code IN ('Ag','In','Pcu','Mn','Sn');
--
-- (d) 8 条 seed（取决于 lp_price_linked_item 是否有这 4 个物料）
-- SELECT b.linked_item_id, li.material_code, b.token_name, b.factor_code, b.price_source, b.source
--   FROM lp_price_variable_binding b
--   JOIN lp_price_linked_item li ON li.id = b.linked_item_id
--  WHERE b.source='EXCEL_INFERRED' AND b.deleted=0
--  ORDER BY li.material_code, b.token_name;
--
-- (e) 权限点
-- SELECT menu_id, menu_name, perms FROM sys_menu WHERE menu_id IN (40156, 40157);
-- SELECT role_id, menu_id FROM sys_role_menu WHERE menu_id IN (40156, 40157) ORDER BY role_id, menu_id;
