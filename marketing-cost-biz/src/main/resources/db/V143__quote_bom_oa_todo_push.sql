-- =============================================================================
-- V143: 报价产品 BOM 准备真实 OA 待办推送字段
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 扩展 lp_bom_supplement_todo，保存真实 OA 待办号、地址、推送状态、失败原因和最近推送时间。
--   2. 保留 V63 的 todo_no / todo_url / todo_status 字段，兼容历史 MOCK 待办记录。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS v143_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v143_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE v143_add_column_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_column_name VARCHAR(64),
  IN p_column_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND COLUMN_NAME = p_column_name
  ) THEN
    SET @v143_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_definition);
    PREPARE v143_add_column_stmt FROM @v143_add_column_sql;
    EXECUTE v143_add_column_stmt;
    DEALLOCATE PREPARE v143_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE v143_add_index_if_not_exists(
  IN p_table_name VARCHAR(64),
  IN p_index_name VARCHAR(64),
  IN p_index_definition TEXT
)
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM INFORMATION_SCHEMA.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = p_table_name
      AND INDEX_NAME = p_index_name
  ) THEN
    SET @v143_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE v143_add_index_stmt FROM @v143_add_index_sql;
    EXECUTE v143_add_index_stmt;
    DEALLOCATE PREPARE v143_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'oa_todo_id',
  '`oa_todo_id` VARCHAR(128) DEFAULT NULL COMMENT ''真实 OA 待办 ID'' AFTER todo_no');
CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'oa_todo_url',
  '`oa_todo_url` VARCHAR(500) DEFAULT NULL COMMENT ''真实 OA 待办跳转地址'' AFTER todo_url');
CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'push_status',
  '`push_status` VARCHAR(32) NOT NULL DEFAULT ''NOT_PUSHED'' COMMENT ''NOT_PUSHED/PUSHED/FAILED/CLOSED/DONE'' AFTER todo_status');
CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'push_error_message',
  '`push_error_message` VARCHAR(1000) DEFAULT NULL COMMENT ''最近一次 OA 推送失败原因'' AFTER push_status');
CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'last_push_at',
  '`last_push_at` DATETIME DEFAULT NULL COMMENT ''最近一次真实 OA 推送时间'' AFTER pushed_at');
CALL v143_add_column_if_not_exists(
  'lp_bom_supplement_todo',
  'closed_at',
  '`closed_at` DATETIME DEFAULT NULL COMMENT ''OA 待办关闭时间'' AFTER last_push_at');

UPDATE lp_bom_supplement_todo
   SET oa_todo_id = COALESCE(oa_todo_id, NULLIF(todo_no, '')),
       oa_todo_url = COALESCE(oa_todo_url, NULLIF(todo_url, '')),
       push_status = CASE
         WHEN push_status IS NULL OR push_status = '' THEN
           CASE
             WHEN todo_status IN ('PUSHED', 'MOCK_PUSHED') THEN 'PUSHED'
             WHEN todo_status = 'FAILED' THEN 'FAILED'
             WHEN todo_status IN ('DONE', 'CLOSED') THEN 'DONE'
             ELSE 'NOT_PUSHED'
           END
         ELSE push_status
       END,
       last_push_at = COALESCE(last_push_at, pushed_at)
 WHERE todo_kind = 'TODO'
   AND recipient_role = 'TECHNICIAN';

CALL v143_add_index_if_not_exists(
  'lp_bom_supplement_todo',
  'idx_bom_supplement_oa_todo_id',
  'ADD INDEX idx_bom_supplement_oa_todo_id (oa_todo_id)');
CALL v143_add_index_if_not_exists(
  'lp_bom_supplement_todo',
  'idx_bom_supplement_todo_push',
  'ADD INDEX idx_bom_supplement_todo_push (push_status, last_push_at)');

DROP PROCEDURE IF EXISTS v143_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS v143_add_index_if_not_exists;
