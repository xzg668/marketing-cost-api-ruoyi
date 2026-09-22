-- 部门领导来自每次 OA 人员提交的负责人映射，不再存放在产品任务上。
-- 如有旧任务级审核人，先归档并核实历史，再执行本迁移。
SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS assert_task_reviewer_retired;
DELIMITER $$
CREATE PROCEDURE assert_task_reviewer_retired()
BEGIN
  DECLARE old_assignments BIGINT DEFAULT 0;
  DECLARE dependent_objects BIGINT DEFAULT 0;

  SELECT COUNT(*) INTO old_assignments FROM lp_quote_tech_task
   WHERE reviewer_user_id IS NOT NULL OR reviewer_name IS NOT NULL;
  IF old_assignments <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 旧任务仍有本地审核人，须先归档';
  END IF;

  SELECT (SELECT COUNT(*) FROM information_schema.triggers
           WHERE trigger_schema=DATABASE() AND event_object_table='lp_quote_tech_task'
             AND (LOWER(action_statement) LIKE '%reviewer_user_id%'
                  OR LOWER(action_statement) LIKE '%reviewer_name%'))
       + (SELECT COUNT(*) FROM information_schema.views
           WHERE table_schema=DATABASE() AND
             (LOWER(view_definition) LIKE '%reviewer_user_id%'
              OR LOWER(view_definition) LIKE '%reviewer_name%'))
       + (SELECT COUNT(*) FROM information_schema.routines
           WHERE routine_schema=DATABASE() AND routine_name<>'assert_task_reviewer_retired' AND
             (LOWER(routine_definition) LIKE '%reviewer_user_id%'
              OR LOWER(routine_definition) LIKE '%reviewer_name%'))
    INTO dependent_objects;
  IF dependent_objects <> 0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TW20_BLOCKED: 数据库对象仍引用任务审核人';
  END IF;
END$$
DELIMITER ;

CALL assert_task_reviewer_retired();
DROP PROCEDURE assert_task_reviewer_retired;

ALTER TABLE lp_quote_tech_task
  DROP COLUMN reviewer_user_id,
  DROP COLUMN reviewer_name;
