-- =============================================================================
-- V91: 物料价格类型对照表按 Excel「价格类型-导入」格式落库
-- -----------------------------------------------------------------------------
-- 当前业务表来源是产品成本计算表里的「价格类型-导入」sheet：
--   物料代码 / 物料名称 / 型号 / 单位 / 物料形态属性 / 主分类(编码) / 主分类(名称) / 价格类型
-- 该 sheet 不带单据号、期间，因此 bill_no / period 改为可空，period 只作为历史期间化路由保留。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v91_add_mpt_column;
DELIMITER $$
CREATE PROCEDURE v91_add_mpt_column(
  IN p_column_name VARCHAR(64),
  IN p_column_def TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'lp_material_price_type'
      AND column_name = p_column_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE lp_material_price_type ADD COLUMN ', p_column_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v91_add_mpt_column('unit', '`unit` VARCHAR(32) NULL COMMENT ''单位'' AFTER `material_model`');
CALL v91_add_mpt_column('category_code', '`category_code` VARCHAR(64) NULL COMMENT ''主分类编码'' AFTER `material_shape`');
CALL v91_add_mpt_column('category_name', '`category_name` VARCHAR(128) NULL COMMENT ''主分类名称'' AFTER `category_code`');

DROP PROCEDURE IF EXISTS v91_add_mpt_column;

ALTER TABLE lp_material_price_type
  MODIFY COLUMN bill_no VARCHAR(64) NULL COMMENT '旧导入单据号，Excel价格类型-导入无此列',
  MODIFY COLUMN period CHAR(7) NULL COMMENT '价格期间；为空表示全局价格类型路由';

DROP PROCEDURE IF EXISTS v91_add_mpt_index;
DELIMITER $$
CREATE PROCEDURE v91_add_mpt_index()
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'lp_material_price_type'
      AND index_name = 'idx_mpt_excel_route'
  ) THEN
    ALTER TABLE lp_material_price_type
      ADD INDEX idx_mpt_excel_route (
        material_code,
        material_model,
        material_shape,
        price_type,
        period
      );
  END IF;
END$$
DELIMITER ;

CALL v91_add_mpt_index();
DROP PROCEDURE IF EXISTS v91_add_mpt_index;
