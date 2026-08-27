-- =============================================================================
-- V238: 收口 CMS 工资/辅料唯一来源并移除未落库的 Cu 差异历史表
--
-- 当前正式核算的工资和辅料金额只读取 cms_cost_source_effective；旧维护表、
-- 派生日志和导入计数没有运行时消费者。Cu 差异由价格准备快照实时计算，当前
-- 核算主链从未向 lp_quote_cu_material_diff_item 写入数据。
-- =============================================================================

SET NAMES utf8mb4;

CREATE TEMPORARY TABLE `tmp_v238_obsolete_menu` (
  `menu_id` BIGINT NOT NULL PRIMARY KEY
);

CREATE TEMPORARY TABLE `tmp_v238_obsolete_menu_child` (
  `menu_id` BIGINT NOT NULL PRIMARY KEY
);

INSERT IGNORE INTO `tmp_v238_obsolete_menu` (`menu_id`) VALUES
  (305), (3051), (3052), (307),
  (40164), (40176), (40182), (40183);

INSERT IGNORE INTO `tmp_v238_obsolete_menu` (`menu_id`)
SELECT `menu_id`
FROM `sys_menu`
WHERE `component` IN (
        'base/auxiliary/subject/index',
        'base/auxiliary/item/index',
        'base/salary/index',
        'base-data/salary-cost/index',
        'rate/aux-rate/index'
      )
   OR `path` IN (
        '/base/aux',
        '/base/aux/subject',
        '/base/aux/item',
        '/base/salary'
      )
   OR `perms` LIKE 'base:salary:%'
   OR `perms` LIKE 'base:aux:%'
   OR `perms` LIKE 'base:aux-%';

-- MySQL does not allow reading and writing the same temporary table in one
-- statement (ERROR 1137). Snapshot direct children first, then merge them.
INSERT IGNORE INTO `tmp_v238_obsolete_menu_child` (`menu_id`)
SELECT child.`menu_id`
FROM `sys_menu` child
JOIN `tmp_v238_obsolete_menu` parent ON parent.`menu_id` = child.`parent_id`;

INSERT IGNORE INTO `tmp_v238_obsolete_menu` (`menu_id`)
SELECT `menu_id`
FROM `tmp_v238_obsolete_menu_child`;

DELETE role_menu
FROM `sys_role_menu` role_menu
JOIN `tmp_v238_obsolete_menu` obsolete ON obsolete.`menu_id` = role_menu.`menu_id`;

DELETE menu
FROM `sys_menu` menu
JOIN `tmp_v238_obsolete_menu` obsolete ON obsolete.`menu_id` = menu.`menu_id`;

DROP TEMPORARY TABLE `tmp_v238_obsolete_menu_child`;
DROP TEMPORARY TABLE `tmp_v238_obsolete_menu`;

ALTER TABLE `cms_cost_import_batch`
  DROP COLUMN `salary_insert_count`,
  DROP COLUMN `salary_skip_count`,
  DROP COLUMN `salary_blocked_count`,
  DROP COLUMN `aux_insert_count`,
  DROP COLUMN `aux_skip_count`;

DROP TABLE IF EXISTS `cms_cost_derive_log`;
DROP TABLE IF EXISTS `lp_salary_cost`;
DROP TABLE IF EXISTS `lp_aux_subject`;
DROP TABLE IF EXISTS `lp_aux_rate_item`;
DROP TABLE IF EXISTS `lp_quote_cu_material_diff_item`;
