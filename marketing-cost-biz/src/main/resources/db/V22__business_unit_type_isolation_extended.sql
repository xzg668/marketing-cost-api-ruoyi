-- =====================================================================
-- V22：业务单元数据隔离 - 扩展覆盖（辅助 / 率表 / 财务基准价 / 物料主数据）
--
--   背景：V21 覆盖了 OA / BOM / 成本结果 / 价格主线等 17 张业务表；
--         但辅助费率、制造率、三项费用率、质量损失率、部门基金率、
--         其它费用率、财务基准价、辅助科目、工资成本、物料主数据等
--         10 张表同样含租户级数据，家用/商用两租户的费率和基准单价
--         不同，必须一并加 business_unit_type 列与索引。
--
--   目标：给以下 10 张表统一补 business_unit_type VARCHAR(20) + idx_*_but
--         索引，历史数据按 V21 约定兜底 COMMERCIAL（系统唯一已投产租户）。
--
--   幂等：沿用 V21 的三段式助手（表不存在 / 列已存在 / 索引已存在均跳过）。
--
--   依赖：V21 末尾已 DROP 了它的辅助过程，所以本脚本独立声明一套 *_v22 过程。
-- =====================================================================

-- --------------------------------------------------------
-- 辅助：幂等加列 / 幂等加索引 / 表缺失安全回填
-- --------------------------------------------------------
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v22;
DELIMITER //
CREATE PROCEDURE add_column_if_not_exists_v22(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def    VARCHAR(500)
)
BEGIN
    -- 表缺失 / 列已存在 均跳过，保证幂等 + 向前兼容
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

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v22;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v22(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_cols  VARCHAR(500)
)
BEGIN
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

DROP PROCEDURE IF EXISTS backfill_default_bu_v22;
DELIMITER //
CREATE PROCEDURE backfill_default_bu_v22(
    IN p_table VARCHAR(64)
)
BEGIN
    -- 表不存在 / 列不存在 均跳过，否则兜底 COMMERCIAL
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name   = p_table
          AND column_name  = 'business_unit_type'
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

-- ============================================================
-- 1. 加列 + 加索引
-- ============================================================

-- 辅助费率项（每物料一行浮动率，家用/商用使用不同浮动率表）
CALL add_column_if_not_exists_v22('lp_aux_rate_item', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v22('lp_aux_rate_item', 'idx_aux_rate_but', '`business_unit_type`');

-- 辅助科目（辅助单价行项，家用/商用定价体系不同）
CALL add_column_if_not_exists_v22('lp_aux_subject', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v22('lp_aux_subject', 'idx_aux_subject_but', '`business_unit_type`');

-- 财务基准价（金属/汇率基准，家用/商用口径可独立）
CALL add_column_if_not_exists_v22('lp_finance_base_price', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `id`");
CALL add_index_if_not_exists_v22('lp_finance_base_price', 'idx_finance_base_price_but', '`business_unit_type`');

-- 制造率（率表按 business_unit 组织口径分的，租户口径也要叠加）
CALL add_column_if_not_exists_v22('lp_manufacture_rate', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径，区别于组织口径 business_unit）' AFTER `business_unit`");
CALL add_index_if_not_exists_v22('lp_manufacture_rate', 'idx_manufacture_rate_but', '`business_unit_type`');

-- 三项费用率（管理 / 销售 / 财务）
CALL add_column_if_not_exists_v22('lp_three_expense_rate', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径）' AFTER `business_unit`");
CALL add_index_if_not_exists_v22('lp_three_expense_rate', 'idx_three_expense_rate_but', '`business_unit_type`');

-- 质量损失率
CALL add_column_if_not_exists_v22('lp_quality_loss_rate', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径）' AFTER `business_unit`");
CALL add_index_if_not_exists_v22('lp_quality_loss_rate', 'idx_quality_loss_rate_but', '`business_unit_type`');

-- 部门基金率
CALL add_column_if_not_exists_v22('lp_department_fund_rate', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径）' AFTER `business_unit`");
CALL add_index_if_not_exists_v22('lp_department_fund_rate', 'idx_department_fund_rate_but', '`business_unit_type`');

-- 其它费用率（按 material_code 维度，家用商用分别维护）
CALL add_column_if_not_exists_v22('lp_other_expense_rate', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v22('lp_other_expense_rate', 'idx_other_expense_rate_but', '`business_unit_type`');

-- 工资成本（直接/间接人工）
CALL add_column_if_not_exists_v22('lp_salary_cost', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元（租户口径）' AFTER `business_unit`");
CALL add_index_if_not_exists_v22('lp_salary_cost', 'idx_salary_cost_but', '`business_unit_type`');

-- 物料主数据（家用可能建立独立物料编号体系，预留隔离）
CALL add_column_if_not_exists_v22('lp_material_master', 'business_unit_type',
    "VARCHAR(20) DEFAULT NULL COMMENT '业务单元' AFTER `material_code`");
CALL add_index_if_not_exists_v22('lp_material_master', 'idx_material_master_but', '`business_unit_type`');

-- ============================================================
-- 2. 历史数据回填
--   系统仅商用已投产，全部兜底 COMMERCIAL；
--   家用数据未来由家用链路导入时显式写 HOUSEHOLD，不会被这里误覆盖（已加 WHERE IS NULL）。
-- ============================================================
CALL backfill_default_bu_v22('lp_aux_rate_item');
CALL backfill_default_bu_v22('lp_aux_subject');
CALL backfill_default_bu_v22('lp_finance_base_price');
CALL backfill_default_bu_v22('lp_manufacture_rate');
CALL backfill_default_bu_v22('lp_three_expense_rate');
CALL backfill_default_bu_v22('lp_quality_loss_rate');
CALL backfill_default_bu_v22('lp_department_fund_rate');
CALL backfill_default_bu_v22('lp_other_expense_rate');
CALL backfill_default_bu_v22('lp_salary_cost');
CALL backfill_default_bu_v22('lp_material_master');

-- ============================================================
-- 3. 清理辅助过程（与 V21 对齐，不留残影）
-- ============================================================
DROP PROCEDURE IF EXISTS add_column_if_not_exists_v22;
DROP PROCEDURE IF EXISTS add_index_if_not_exists_v22;
DROP PROCEDURE IF EXISTS backfill_default_bu_v22;
