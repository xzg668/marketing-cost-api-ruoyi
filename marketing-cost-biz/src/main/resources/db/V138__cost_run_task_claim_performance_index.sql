-- =============================================================================
-- V138: 通用成本核算任务抢占性能索引
-- -----------------------------------------------------------------------------
-- 目标：
--   1. 支撑多 Worker 按 scene + status + lock_expire_time 抢占任务。
--   2. 对已执行过 V137 的环境补齐复合索引；V137 新建库也已包含同名索引。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v138;
DELIMITER //
CREATE PROCEDURE add_index_if_not_exists_v138(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_def TEXT
)
BEGIN
  IF EXISTS (
    SELECT 1
      FROM information_schema.TABLES
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
  ) AND NOT EXISTS (
    SELECT 1
      FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = p_table_name
       AND INDEX_NAME = p_index_name
  ) THEN
    SET @ddl = CONCAT('ALTER TABLE `', p_table_name, '` ADD ', p_index_def);
    PREPARE stmt FROM @ddl;
    EXECUTE stmt;
    DEALLOCATE PREPARE stmt;
  END IF;
END//
DELIMITER ;

CALL add_index_if_not_exists_v138('lp_cost_run_task', 'idx_cost_run_task_scene_claim',
  'KEY idx_cost_run_task_scene_claim (scene, status, lock_expire_time, id)');

DROP PROCEDURE IF EXISTS add_index_if_not_exists_v138;
