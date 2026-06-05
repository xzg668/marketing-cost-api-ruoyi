-- 供应商供货比例导入去重口径调整：
-- 旧口径：业务单元 + 物料代码 + 物料名称 + 供应商 + 型号 + deleted
-- 新口径：业务单元 + 物料代码 + 供应商 + deleted

DROP PROCEDURE IF EXISTS v152_drop_index_if_exists;
DELIMITER $$
CREATE PROCEDURE v152_drop_index_if_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128)
)
BEGIN
  IF EXISTS (
      SELECT 1
        FROM information_schema.statistics
       WHERE table_schema = DATABASE()
         AND table_name = p_table_name
         AND index_name = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` DROP INDEX `', p_index_name, '`');
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v152_drop_index_if_exists('lp_supplier_supply_ratio', 'uk_supplier_ratio_biz');
DROP PROCEDURE IF EXISTS v152_drop_index_if_exists;

DROP PROCEDURE IF EXISTS v152_add_index_if_not_exists;

UPDATE sys_menu
   SET remark = '维护供应商供货比例；导入时按业务单元、物料代码、供应商去重',
       update_time = NOW()
 WHERE menu_id = 40427;

ALTER TABLE `lp_supplier_supply_ratio`
  MODIFY COLUMN `spec_model` VARCHAR(255) NOT NULL DEFAULT '' COMMENT '型号/规格型号';
DELIMITER $$
CREATE PROCEDURE v152_add_index_if_not_exists(
  IN p_table_name VARCHAR(128),
  IN p_index_name VARCHAR(128),
  IN p_index_ddl TEXT
)
BEGIN
  IF NOT EXISTS (
      SELECT 1
        FROM information_schema.statistics
       WHERE table_schema = DATABASE()
         AND table_name = p_table_name
         AND index_name = p_index_name
  ) THEN
    SET @sql = p_index_ddl;
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$
DELIMITER ;

CALL v152_add_index_if_not_exists(
  'lp_supplier_supply_ratio',
  'uk_supplier_ratio_biz',
  'ALTER TABLE `lp_supplier_supply_ratio`
     ADD UNIQUE KEY `uk_supplier_ratio_biz`
       (`business_unit_type`, `material_code`, `supplier_name`, `deleted`)'
);

DROP PROCEDURE IF EXISTS v152_add_index_if_not_exists;
