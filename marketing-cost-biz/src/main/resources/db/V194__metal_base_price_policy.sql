-- =====================================================================
-- V194: Zn/Al 金属基价取值策略与简化菜单
--
-- OA_PRIORITY   : OA 有基价时优先使用，OA 为空时回退当月影响因素价格。
-- FACTOR_MONTHLY: 不读取 OA，直接使用核算月份影响因素价格。
-- Cu 由“财务Cu报价基准”按月维护，不开放本策略切换。
-- =====================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v194_add_column_if_not_exists;

DELIMITER $$

CREATE PROCEDURE v194_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND COLUMN_NAME = p_column_name
  ) THEN
    SET @ddl = CONCAT(
      'ALTER TABLE `', p_table_name, '` ADD COLUMN `', p_column_name, '` ', p_column_definition);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END$$

DELIMITER ;

CALL v194_add_column_if_not_exists(
  'lp_quote_base_price_mapping_rule',
  'price_policy',
  'VARCHAR(32) NOT NULL DEFAULT ''OA_PRIORITY'' COMMENT ''OA_PRIORITY=OA优先；FACTOR_MONTHLY=影响因素表'' AFTER `priority`'
);

CALL v194_add_column_if_not_exists(
  'lp_factor_quote_base_mapping',
  'price_policy',
  'VARCHAR(32) NOT NULL DEFAULT ''OA_PRIORITY'' COMMENT ''OA_PRIORITY=OA优先；FACTOR_MONTHLY=影响因素表'' AFTER `confidence`'
);

DROP PROCEDURE IF EXISTS v194_add_column_if_not_exists;

UPDATE lp_quote_base_price_mapping_rule
   SET price_policy = 'OA_PRIORITY'
 WHERE price_policy IS NULL
    OR price_policy NOT IN ('OA_PRIORITY', 'FACTOR_MONTHLY');

UPDATE lp_factor_quote_base_mapping
   SET price_policy = 'OA_PRIORITY'
 WHERE price_policy IS NULL
    OR price_policy NOT IN ('OA_PRIORITY', 'FACTOR_MONTHLY');

START TRANSACTION;

UPDATE sys_menu
SET menu_name = '金属基价取值规则',
    parent_id = 40475,
    order_num = 40,
    visible = '0',
    status = '0',
    update_by = 'V194',
    update_time = NOW(),
    remark = '规则配置：Zn、Al选择OA优先或核算月份影响因素价格；Cu使用财务Cu月度基准'
WHERE menu_id = 40421;

UPDATE sys_menu
SET menu_name = CASE menu_id
      WHEN 40422 THEN '金属基价取值规则查看'
      WHEN 40424 THEN '金属基价取值规则编辑'
      ELSE menu_name
    END,
    update_by = 'V194',
    update_time = NOW()
WHERE menu_id IN (40422, 40424);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role_id, 40475
FROM sys_role_menu
WHERE menu_id = 40421;

COMMIT;
