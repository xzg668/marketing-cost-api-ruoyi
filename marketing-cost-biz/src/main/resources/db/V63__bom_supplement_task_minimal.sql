-- =============================================================================
-- V63: BOM 补录任务最小闭环
-- -----------------------------------------------------------------------------
-- 范围：
--   1. 新增 BOM 缺失补录任务表。
--   2. 新增补录任务与报价单产品行关联表。
--   3. 新增模拟 OA 待办 / 通知记录表。
--   4. 为 lp_quote_bom_status 预留 supplement_task_id。
-- =============================================================================

DROP PROCEDURE IF EXISTS _bom_supp_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _bom_supp_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE _bom_supp_add_column_if_not_exists(
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
    SET @bom_supp_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ADD COLUMN ', p_column_definition);
    PREPARE bom_supp_add_column_stmt FROM @bom_supp_add_column_sql;
    EXECUTE bom_supp_add_column_stmt;
    DEALLOCATE PREPARE bom_supp_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _bom_supp_add_index_if_not_exists(
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
    SET @bom_supp_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE bom_supp_add_index_stmt FROM @bom_supp_add_index_sql;
    EXECUTE bom_supp_add_index_stmt;
    DEALLOCATE PREPARE bom_supp_add_index_stmt;
  END IF;
END //

DELIMITER ;

CREATE TABLE IF NOT EXISTS lp_bom_supplement_task (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_no VARCHAR(64) NOT NULL COMMENT '补录任务号',
  business_unit_type VARCHAR(32) DEFAULT NULL COMMENT '业务单元',
  product_code VARCHAR(64) NOT NULL COMMENT '产品料号',
  product_name VARCHAR(255) DEFAULT NULL COMMENT '产品名称',
  product_model VARCHAR(128) DEFAULT NULL COMMENT '三花型号',
  customer_code VARCHAR(128) DEFAULT NULL COMMENT '客户编码',
  package_type VARCHAR(128) DEFAULT NULL COMMENT '包装类型',
  package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式',
  missing_bom_scope VARCHAR(32) NOT NULL DEFAULT 'PRODUCT_BOM' COMMENT 'PRODUCT_BOM/PACKAGE_BOM/QUOTE_BOM',
  missing_reason VARCHAR(64) DEFAULT NULL COMMENT '缺失原因',
  task_status VARCHAR(32) NOT NULL DEFAULT 'TODO_PUSHED' COMMENT 'TODO_PENDING/TODO_PUSHED/IN_PROGRESS/FINANCE_REVIEW/CONFIRMED/CANCELLED',
  technician_name VARCHAR(128) NOT NULL COMMENT '技术员',
  due_at DATETIME DEFAULT NULL COMMENT '要求完成时间',
  remark VARCHAR(500) DEFAULT NULL COMMENT '备注',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_supplement_task_no (task_no),
  KEY idx_bom_supplement_active_product (missing_bom_scope, product_code, task_status),
  KEY idx_bom_supplement_technician (technician_name, task_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BOM缺失补录任务表';

CREATE TABLE IF NOT EXISTS lp_bom_supplement_task_quote_link (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL COMMENT '补录任务 ID',
  task_no VARCHAR(64) NOT NULL COMMENT '补录任务号',
  quote_bom_status_id BIGINT NOT NULL COMMENT 'lp_quote_bom_status.id',
  oa_form_id BIGINT NOT NULL COMMENT 'oa_form.id',
  oa_form_item_id BIGINT NOT NULL COMMENT 'oa_form_item.id',
  oa_no VARCHAR(64) NOT NULL COMMENT '报价单号',
  product_code VARCHAR(64) DEFAULT NULL COMMENT '产品料号',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_supplement_link_status (quote_bom_status_id),
  KEY idx_bom_supplement_link_task (task_id),
  KEY idx_bom_supplement_link_oa (oa_no, oa_form_item_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BOM补录任务关联报价单产品行表';

CREATE TABLE IF NOT EXISTS lp_bom_supplement_todo (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL COMMENT '补录任务 ID',
  task_no VARCHAR(64) NOT NULL COMMENT '补录任务号',
  todo_no VARCHAR(128) NOT NULL COMMENT '泛微 OA 待办编号，当前为 MOCK 编号',
  todo_status VARCHAR(32) NOT NULL DEFAULT 'MOCK_PUSHED' COMMENT 'PENDING/MOCK_PUSHED/PUSHED/FAILED/DONE',
  todo_kind VARCHAR(32) NOT NULL DEFAULT 'TODO' COMMENT 'TODO/NOTICE/CC',
  recipient_role VARCHAR(32) NOT NULL COMMENT 'TECHNICIAN/QUOTE_OWNER',
  assignee_name VARCHAR(128) DEFAULT NULL COMMENT '待办接收人',
  title VARCHAR(255) NOT NULL COMMENT '待办标题',
  todo_url VARCHAR(500) DEFAULT NULL COMMENT '待办跳转地址',
  payload_json JSON DEFAULT NULL COMMENT '模拟待办报文',
  pushed_at DATETIME DEFAULT NULL COMMENT '推送时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bom_supplement_todo_kind (task_id, recipient_role, todo_kind),
  KEY idx_bom_supplement_todo_no (todo_no),
  KEY idx_bom_supplement_todo_status (todo_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='BOM补录OA待办推送记录表';

CALL _bom_supp_add_column_if_not_exists('lp_quote_bom_status', 'supplement_task_id',
  'supplement_task_id BIGINT DEFAULT NULL COMMENT ''BOM补录任务ID'' AFTER manual_task_no');
CALL _bom_supp_add_index_if_not_exists('lp_quote_bom_status', 'idx_quote_bom_status_supp_task',
  'ADD INDEX idx_quote_bom_status_supp_task (supplement_task_id)');

DROP PROCEDURE IF EXISTS _bom_supp_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _bom_supp_add_index_if_not_exists;
