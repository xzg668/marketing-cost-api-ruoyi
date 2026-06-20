-- V160: align quote BOM check statuses with business-facing rules.

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v160_add_index_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v160_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM INFORMATION_SCHEMA.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @sql = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @sql;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END $$

DELIMITER ;

UPDATE sys_dict_data
   SET dict_label = 'BOM 当月发起过报价',
       remark = '当月 lp_bom_costing_row 已存在该产品料号报价核算数据',
       update_time = NOW()
 WHERE dict_type = 'quote_bom_status'
   AND dict_value = 'CURRENT_MONTH_QUOTED'
   AND dict_label <> 'BOM 当月发起过报价';

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark, create_time)
SELECT 3, 'BOM 当月发起过报价', 'CURRENT_MONTH_QUOTED', 'quote_bom_status', '0',
       '当月 lp_bom_costing_row 已存在该产品料号报价核算数据', NOW()
  FROM DUAL
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_dict_data
        WHERE dict_type = 'quote_bom_status'
          AND dict_value = 'CURRENT_MONTH_QUOTED'
 );

UPDATE sys_dict_data
   SET dict_label = 'U9 有此 BOM',
       remark = 'lp_bom_raw_hierarchy 已存在该产品料号 BOM',
       update_time = NOW()
 WHERE dict_type = 'quote_bom_status'
   AND dict_value = 'U9_BOM_EXISTS'
   AND dict_label <> 'U9 有此 BOM';

INSERT INTO sys_dict_data (dict_sort, dict_label, dict_value, dict_type, status, remark, create_time)
SELECT 4, 'U9 有此 BOM', 'U9_BOM_EXISTS', 'quote_bom_status', '0',
       'lp_bom_raw_hierarchy 已存在该产品料号 BOM', NOW()
  FROM DUAL
 WHERE NOT EXISTS (
       SELECT 1 FROM sys_dict_data
        WHERE dict_type = 'quote_bom_status'
          AND dict_value = 'U9_BOM_EXISTS'
 );

UPDATE sys_dict_data
   SET dict_sort = CASE dict_value
                    WHEN 'NO_BOM' THEN 5
                    WHEN 'ENTRY_PENDING' THEN 6
                    WHEN 'ENTRY_IN_PROGRESS' THEN 7
                    WHEN 'MANUAL_ENTERED' THEN 8
                    WHEN 'EXPIRED' THEN 9
                    WHEN 'CHECK_FAILED' THEN 10
                    ELSE dict_sort
                   END,
       update_time = NOW()
 WHERE dict_type = 'quote_bom_status'
   AND dict_value IN (
       'NO_BOM',
       'ENTRY_PENDING',
       'ENTRY_IN_PROGRESS',
       'MANUAL_ENTERED',
       'EXPIRED',
       'CHECK_FAILED'
   );

CALL v160_add_index_if_not_exists(
  'lp_bom_costing_row',
  'idx_bom_costing_top_period',
  'KEY idx_bom_costing_top_period (top_product_code, period_month)'
);

DROP PROCEDURE IF EXISTS v160_add_index_if_not_exists;
