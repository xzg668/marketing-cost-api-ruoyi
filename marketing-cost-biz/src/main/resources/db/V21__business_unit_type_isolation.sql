-- =====================================================================
-- V21：业务单元数据隔离（COMMERCIAL vs HOUSEHOLD）
--
--   背景：sys_user.business_unit_type 已存在（V4），BusinessUnitInterceptor
--         也已实现 WHERE 注入，但业务表均未加 business_unit_type 字段，
--         导致 @DataScope 注入 "AND business_unit_type = 'HOUSEHOLD'" 时
--         直接 SQL 报错；另一侧：家用账号若绕过 @DataScope 直接查询，就能
--         看到商用租户的 OA / BOM / 成本结果，存在数据越权。
--
--   目标：给所有业务数据表统一补 business_unit_type VARCHAR(20) + 索引，
--         并对历史数据按 oa_form.form_type 前缀回填：
--           · 家用% → HOUSEHOLD
--           · 其它  → COMMERCIAL（当前系统仅有商用，默认兜底）
--
--   幂等：沿用 V4 的 add_column_if_not_exists；索引走 information_schema 检查。
--
--   注意：lp_cost_run_result 已有 business_unit VARCHAR(120) 字段（组织口径，
--         如"四通阀事业部"），与 business_unit_type（租户口径，
--         COMMERCIAL/HOUSEHOLD）不同，不可复用，必须新增一列。
-- =====================================================================

-- --------------------------------------------------------
-- 辅助：幂等加列 / 幂等加索引
-- --------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v21;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v21(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def    VARCHAR(500)
)
BEGIN
    -- 优先判断表是否存在：部分表（如 lp_price_range_item）在某些环境尚未创建，跳过即可
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- 表缺失安全的默认回填：若表不存在则跳过，否则兜底 COMMERCIAL
DROP PROCEDURE IF EXISTS backfill_default_bu_v21;
DELIMITER //
CREATE PROCEDURE backfill_default_bu_v21(
    IN p_table VARCHAR(64)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
    ) THEN
        SET @ddl = CONCAT(
            'UPDATE `', p_table, '` SET `business_unit_type` = ''COMMERCIAL'' ',
            'WHERE `business_unit_type` IS NULL');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v21;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v21(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols  VARCHAR(500)
)
BEGIN
    -- 表缺失时静默跳过，保证 V21 在半全量环境也能安全执行
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND index_name   = p_index
    ) THEN
        SET @ddl = CONCAT('CREATE INDEX `', p_index, '` ON `', p_table, '` (', p_cols, ')');
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

-- ============================================================
-- 1. 业务主表（OA / BOM / 成本结果）—— 严格隔离
-- ============================================================
-- oa_form —— 源头，form_type 前缀是回填关键
CALL add_column_if_not_exists_v21('oa_form', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（COMMERCIAL 商用 / HOUSEHOLD 家用）' AFTER `customer`");
CALL add_index_if_not_exists_v21('oa_form', 'idx_oa_form_but', '`business_unit_type`');

-- oa_form_item —— 此表无 oa_no 字段，通过 oa_form_id 外键关联 oa_form 回填
CALL add_column_if_not_exists_v21('oa_form_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元，冗余字段便于过滤' AFTER `oa_form_id`");
CALL add_index_if_not_exists_v21('oa_form_item', 'idx_oa_form_item_but', '`business_unit_type`');

-- BOM 管理明细（采购件 / 联动件）
CALL add_column_if_not_exists_v21('lp_bom_manage_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `oa_no`");
CALL add_index_if_not_exists_v21('lp_bom_manage_item', 'idx_bom_manage_but', '`business_unit_type`');

-- BOM 手工明细（自制件）—— 此表无 oa_no，以 bom_code 为主键；业务单元直接挂 bom_code 后
CALL add_column_if_not_exists_v21('lp_bom_manual_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `bom_code`");
CALL add_index_if_not_exists_v21('lp_bom_manual_item', 'idx_bom_manual_but', '`business_unit_type`');

-- 成本试算汇总
CALL add_column_if_not_exists_v21('lp_cost_run_result', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径，区别于组织口径 business_unit）' AFTER `business_unit`");
CALL add_index_if_not_exists_v21('lp_cost_run_result', 'idx_cost_run_result_but', '`business_unit_type`');

-- 成本试算-部品行
CALL add_column_if_not_exists_v21('lp_cost_run_part_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `oa_no`");
CALL add_index_if_not_exists_v21('lp_cost_run_part_item', 'idx_cost_run_part_but', '`business_unit_type`');

-- 成本试算-成本项
CALL add_column_if_not_exists_v21('lp_cost_run_cost_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `oa_no`");
CALL add_index_if_not_exists_v21('lp_cost_run_cost_item', 'idx_cost_run_cost_but', '`business_unit_type`');

-- ============================================================
-- 2. 取价相关数据表（固定价 / 联动价 / 结算价 / 区间价 / 制造件规格 / 原材料拆解）
--    价格数据需按租户隔离：商用家用完全不同体系，共享会串价。
-- ============================================================
CALL add_column_if_not_exists_v21('lp_price_fixed_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v21('lp_price_fixed_item', 'idx_price_fixed_but', '`business_unit_type`');

CALL add_column_if_not_exists_v21('lp_price_linked_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v21('lp_price_linked_item', 'idx_price_linked_but', '`business_unit_type`');

-- lp_price_linked_calc_item 使用 item_code 作为物料键（非 material_code），因此业务单元挂在 item_code 之后
CALL add_column_if_not_exists_v21('lp_price_linked_calc_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `item_code`");
CALL add_index_if_not_exists_v21('lp_price_linked_calc_item', 'idx_price_linked_calc_but', '`business_unit_type`');

CALL add_column_if_not_exists_v21('lp_price_settle', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `id`");
CALL add_index_if_not_exists_v21('lp_price_settle', 'idx_price_settle_but', '`business_unit_type`');

CALL add_column_if_not_exists_v21('lp_price_settle_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `id`");
CALL add_index_if_not_exists_v21('lp_price_settle_item', 'idx_price_settle_item_but', '`business_unit_type`');

-- 区间价表（如果存在）
CALL add_column_if_not_exists_v21('lp_price_range_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `id`");
CALL add_index_if_not_exists_v21('lp_price_range_item', 'idx_price_range_but', '`business_unit_type`');

-- 制造件规格（V14 新增）
CALL add_column_if_not_exists_v21('lp_make_part_spec', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v21('lp_make_part_spec', 'idx_make_part_spec_but', '`business_unit_type`');

-- 原材料拆解（V15 新增）—— V15 实际列名为 parent_code（非 parent_material_code）
CALL add_column_if_not_exists_v21('lp_raw_material_breakdown', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `parent_code`");
CALL add_index_if_not_exists_v21('lp_raw_material_breakdown', 'idx_raw_breakdown_but', '`business_unit_type`');

-- ============================================================
-- 3. 产品属性 / 变量注册 / 取价路由（半租户数据）
-- ============================================================
-- lp_product_property 无 material_code 列（自然键为 parent_code），业务单元挂 parent_code 后
CALL add_column_if_not_exists_v21('lp_product_property', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `parent_code`");
CALL add_index_if_not_exists_v21('lp_product_property', 'idx_product_property_but', '`business_unit_type`');

CALL add_column_if_not_exists_v21('lp_price_variable', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `id`");
CALL add_index_if_not_exists_v21('lp_price_variable', 'idx_price_variable_but', '`business_unit_type`');

CALL add_column_if_not_exists_v21('lp_material_price_type', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v21('lp_material_price_type', 'idx_material_price_type_but', '`business_unit_type`');

-- ============================================================
-- 4. 历史数据回填
-- ============================================================

-- oa_form：按 form_type 前缀 "家用" 识别家用数据，否则统一 COMMERCIAL
UPDATE `oa_form`
SET `business_unit_type` = CASE
    WHEN `form_type` LIKE '家用%' THEN 'HOUSEHOLD'
    ELSE 'COMMERCIAL'
END
WHERE `business_unit_type` IS NULL;

-- oa_form_item：按 oa_form_id 关联回填（此表无 oa_no 字段，走外键 id）
UPDATE `oa_form_item` AS t
    JOIN `oa_form` AS f ON f.`id` = t.`oa_form_id`
SET t.`business_unit_type` = f.`business_unit_type`
WHERE t.`business_unit_type` IS NULL;

-- lp_bom_manage_item：此表有 oa_no，按 oa_no join 回填
UPDATE `lp_bom_manage_item` AS t
    JOIN `oa_form` AS f ON f.`oa_no` = t.`oa_no`
SET t.`business_unit_type` = f.`business_unit_type`
WHERE t.`business_unit_type` IS NULL;

-- lp_bom_manual_item：此表仅以 bom_code 关联，历史数据无法追溯 OA/租户，
-- 默认兜底 COMMERCIAL（当前系统唯一已投产租户）；家用数据后续由导入链路 / 业务方显式标记。
UPDATE `lp_bom_manual_item` SET `business_unit_type` = 'COMMERCIAL'
WHERE `business_unit_type` IS NULL;

-- 成本试算结果：按 oa_no join 回填
UPDATE `lp_cost_run_result` AS t
    JOIN `oa_form` AS f ON f.`oa_no` = t.`oa_no`
SET t.`business_unit_type` = f.`business_unit_type`
WHERE t.`business_unit_type` IS NULL;

UPDATE `lp_cost_run_part_item` AS t
    JOIN `oa_form` AS f ON f.`oa_no` = t.`oa_no`
SET t.`business_unit_type` = f.`business_unit_type`
WHERE t.`business_unit_type` IS NULL;

UPDATE `lp_cost_run_cost_item` AS t
    JOIN `oa_form` AS f ON f.`oa_no` = t.`oa_no`
SET t.`business_unit_type` = f.`business_unit_type`
WHERE t.`business_unit_type` IS NULL;

-- 价格 / 产品属性 / 变量 / 路由 表：
-- 无从追溯原始租户，默认全部归入 COMMERCIAL（当前系统唯一已投产租户），
-- 家用数据需在 T08（家用结算价导入）时由业务方显式标记 HOUSEHOLD。
-- 使用 backfill_default_bu_v21，安全跳过尚未创建的可选表（如 lp_price_range_item）。
CALL backfill_default_bu_v21('lp_price_fixed_item');
CALL backfill_default_bu_v21('lp_price_linked_item');
CALL backfill_default_bu_v21('lp_price_linked_calc_item');
CALL backfill_default_bu_v21('lp_price_settle');
CALL backfill_default_bu_v21('lp_price_settle_item');
CALL backfill_default_bu_v21('lp_price_range_item');
CALL backfill_default_bu_v21('lp_make_part_spec');
CALL backfill_default_bu_v21('lp_raw_material_breakdown');
CALL backfill_default_bu_v21('lp_product_property');
CALL backfill_default_bu_v21('lp_price_variable');
CALL backfill_default_bu_v21('lp_material_price_type');

-- ============================================================
-- 5. 清理辅助过程
-- ============================================================
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v21;
DROP PROCEDURE IF EXISTS add_index_if_not_exists_v21;
DROP PROCEDURE IF EXISTS backfill_default_bu_v21;
