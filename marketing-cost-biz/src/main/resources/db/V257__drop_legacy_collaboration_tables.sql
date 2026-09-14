-- T15：新技术资料链切换完成后，安全删除11张旧协作表。
-- 执行前必须在发布流程外部完成切换时点备份、恢复校验和业务/数据库授权。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `assert_t15_legacy_drop_ready`;
DELIMITER $$
CREATE PROCEDURE `assert_t15_legacy_drop_ready`()
BEGIN
  DECLARE new_table_count INT DEFAULT 0;
  DECLARE old_table_count INT DEFAULT 0;
  DECLARE old_menu_count INT DEFAULT 0;
  DECLARE old_queue_status_count INT DEFAULT 0;
  DECLARE old_view_count INT DEFAULT 0;
  DECLARE old_trigger_count INT DEFAULT 0;

  SELECT COUNT(*) INTO new_table_count
    FROM information_schema.tables
   WHERE table_schema=DATABASE()
     AND table_name IN (
       'lp_quote_tech_task',
       'lp_quote_tech_product',
       'lp_quote_tech_module',
       'lp_quote_tech_data_version',
       'lp_quote_tech_package_item',
       'lp_quote_tech_aux_item',
       'lp_quote_tech_salary_item',
       'lp_quote_tech_review_item'
     );
  IF new_table_count <> 8 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 8张新技术资料表不完整';
  END IF;

  SELECT COUNT(*) INTO old_table_count
    FROM information_schema.tables
   WHERE table_schema=DATABASE()
     AND table_name IN (
       'lp_quote_collaboration_product_task',
       'lp_quote_collaboration_quote_link',
       'lp_quote_collaboration_gap',
       'lp_quote_price_draft_field',
       'lp_quote_collaboration_review_item',
       'lp_quote_collaboration_approved_result',
       'lp_quote_collaboration_admin_action',
       'lp_quote_collaboration_review',
       'lp_quote_price_draft',
       'lp_quote_collaboration_task',
       'lp_collaboration_token'
     );
  IF old_table_count NOT IN (0, 11) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 11张旧表处于非完整状态，禁止部分删表';
  END IF;

  SELECT COUNT(*) INTO old_menu_count
    FROM sys_menu
   WHERE perms LIKE 'collaboration:%'
      OR component IN ('collaboration/technical/index','collaboration/finance/index');
  IF old_menu_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 旧菜单或权限尚未完成切换';
  END IF;

  SELECT COUNT(*) INTO old_queue_status_count
    FROM lp_cost_run_task
   WHERE status='COLLABORATION';
  IF old_queue_status_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 成本队列仍存在旧协作状态';
  END IF;

  SELECT COUNT(*) INTO old_view_count
    FROM information_schema.views
   WHERE table_schema=DATABASE()
     AND LOWER(view_definition) REGEXP
       'lp_quote_collaboration_|lp_quote_price_draft|lp_collaboration_token';
  IF old_view_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 数据库视图仍引用旧表';
  END IF;

  SELECT COUNT(*) INTO old_trigger_count
    FROM information_schema.triggers
   WHERE trigger_schema=DATABASE()
     AND LOWER(action_statement) REGEXP
       'lp_quote_collaboration_|lp_quote_price_draft|lp_collaboration_token';
  IF old_trigger_count <> 0 THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT='T15_BLOCKED: 数据库触发器仍引用旧表';
  END IF;
END$$
DELIMITER ;

CALL `assert_t15_legacy_drop_ready`();
DROP PROCEDURE `assert_t15_legacy_drop_ready`;

-- 当前旧表没有物理外键；仍按代码层逻辑依赖从明细、关联、结果到主任务删除。
DROP TABLE IF EXISTS
  `lp_quote_price_draft_field`,
  `lp_quote_collaboration_review_item`,
  `lp_quote_collaboration_gap`,
  `lp_quote_collaboration_quote_link`,
  `lp_quote_collaboration_approved_result`,
  `lp_quote_collaboration_admin_action`,
  `lp_quote_collaboration_review`,
  `lp_quote_price_draft`,
  `lp_quote_collaboration_product_task`,
  `lp_quote_collaboration_task`,
  `lp_collaboration_token`;
