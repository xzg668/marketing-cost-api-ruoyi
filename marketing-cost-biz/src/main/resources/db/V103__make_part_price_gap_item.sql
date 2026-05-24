-- =============================================================================
-- V103: 制造件价格生成缺价清单
-- -----------------------------------------------------------------------------
-- 说明：
--   1. 主明细补充价格月份和是否完整取价，便于页面判断本次生成是否真的拿到原材料价和废料价。
--   2. 缺价清单按“要补价的料号”拆行，RAW 表示缺原材料价，SCRAP 表示缺废料价。
--   3. 本表只沉淀后续 OA 补价需要的数据，不在本次迁移和本阶段业务中触发 OA 推送。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v103_add_column_if_not_exists;
DELIMITER $$
CREATE PROCEDURE v103_add_column_if_not_exists(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_def    VARCHAR(1000)
)
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = DATABASE()
          AND table_name = p_table
    ) AND NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE()
          AND table_name = p_table
          AND column_name = p_column
    ) THEN
        SET @ddl = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN `', p_column, '` ', p_def);
        PREPARE stmt FROM @ddl;
        EXECUTE stmt;
        DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

CALL v103_add_column_if_not_exists(
    'lp_make_part_price_calc_row',
    'pricing_month',
    "VARCHAR(7) DEFAULT NULL COMMENT '价格月份 YYYY-MM；为空表示历史生成数据' AFTER `business_unit_type`");

CALL v103_add_column_if_not_exists(
    'lp_make_part_price_calc_row',
    'price_complete',
    "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完整取到原材料价和废料价：1 是，0 否' AFTER `parent_total_cost_price`");

UPDATE lp_make_part_price_calc_row
SET pricing_month = DATE_FORMAT(CURRENT_DATE(), '%Y-%m')
WHERE pricing_month IS NULL OR pricing_month = '';

CREATE TABLE IF NOT EXISTS lp_make_part_price_gap_item (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  calc_batch_id VARCHAR(64) NOT NULL COMMENT '生成批次ID',
  pricing_month VARCHAR(7) DEFAULT NULL COMMENT '价格月份 YYYY-MM',
  generated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '生成时间',
  oa_no VARCHAR(64) DEFAULT NULL COMMENT 'OA单号；全量生成时可为空',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元类型',
  parent_material_no VARCHAR(64) NOT NULL COMMENT '制造件料号/原始料号',
  parent_material_name VARCHAR(180) DEFAULT NULL COMMENT '制造件名称',
  child_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原材料/毛坯料号',
  child_material_name VARCHAR(180) DEFAULT NULL COMMENT '原材料/毛坯名称',
  child_material_spec VARCHAR(255) DEFAULT NULL COMMENT '原材料/毛坯规格',
  scrap_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '回收废料料号',
  scrap_name VARCHAR(180) DEFAULT NULL COMMENT '回收废料名称',
  missing_price_role VARCHAR(16) NOT NULL COMMENT '缺价类型：RAW 原材料价 / SCRAP 废料价',
  missing_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '真正要补价的料号',
  missing_material_name VARCHAR(180) DEFAULT NULL COMMENT '真正要补价的物料名称',
  price_type VARCHAR(32) DEFAULT NULL COMMENT '当前路由到的价格类型；取不到可为空',
  reason VARCHAR(500) DEFAULT NULL COMMENT '缺价原因',
  oa_push_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PUSHED' COMMENT 'OA补价推送状态预留：NOT_PUSHED/PUSHED/FAILED；本阶段只默认 NOT_PUSHED',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_make_gap_batch_role (
    calc_batch_id,
    parent_material_no,
    child_material_no,
    scrap_code,
    missing_price_role
  ),
  KEY idx_make_gap_batch (calc_batch_id),
  KEY idx_make_gap_pricing_month (pricing_month),
  KEY idx_make_gap_oa_bu (oa_no, business_unit_type),
  KEY idx_make_gap_parent (parent_material_no),
  KEY idx_make_gap_child (child_material_no),
  KEY idx_make_gap_scrap (scrap_code),
  KEY idx_make_gap_missing_material (missing_material_no),
  KEY idx_make_gap_role_status (missing_price_role, oa_push_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='制造件价格生成缺价清单';

UPDATE lp_make_part_price_gap_item
SET pricing_month = DATE_FORMAT(CURRENT_DATE(), '%Y-%m')
WHERE pricing_month IS NULL OR pricing_month = '';

DROP PROCEDURE IF EXISTS v103_add_column_if_not_exists;
