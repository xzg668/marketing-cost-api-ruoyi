-- =============================================================================
-- V66: CMS 科目设置字典
-- -----------------------------------------------------------------------------
-- 科目设置导出是科目归类和编码字典，不提供金额。
-- 公共生效来源用它识别：
--   - 辅料：一级科目=辅助材料，排除二级科目=包装辅料，按二级科目编码匹配金额。
--   - 辅助员工工资：一级科目=工资，二级科目=辅助人员工资，按字典里的二级科目编码匹配金额。
-- =============================================================================

DROP PROCEDURE IF EXISTS _cms_subject_setting_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_subject_setting_add_index_if_not_exists;

DELIMITER //

CREATE PROCEDURE _cms_subject_setting_add_column_if_not_exists(
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
    SET @cms_subject_setting_add_column_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_column_definition);
    PREPARE cms_subject_setting_add_column_stmt FROM @cms_subject_setting_add_column_sql;
    EXECUTE cms_subject_setting_add_column_stmt;
    DEALLOCATE PREPARE cms_subject_setting_add_column_stmt;
  END IF;
END //

CREATE PROCEDURE _cms_subject_setting_add_index_if_not_exists(
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
    SET @cms_subject_setting_add_index_sql =
      CONCAT('ALTER TABLE ', p_table_name, ' ', p_index_definition);
    PREPARE cms_subject_setting_add_index_stmt FROM @cms_subject_setting_add_index_sql;
    EXECUTE cms_subject_setting_add_index_stmt;
    DEALLOCATE PREPARE cms_subject_setting_add_index_stmt;
  END IF;
END //

DELIMITER ;

CALL _cms_subject_setting_add_column_if_not_exists(
  'cms_cost_import_batch',
  'subject_setting_file_name',
  'ADD COLUMN subject_setting_file_name VARCHAR(255) DEFAULT NULL COMMENT ''科目设置导出文件名'' AFTER subject_file_name'
);

CALL _cms_subject_setting_add_column_if_not_exists(
  'cms_cost_import_batch',
  'subject_setting_row_count',
  'ADD COLUMN subject_setting_row_count INT NOT NULL DEFAULT 0 COMMENT ''科目设置字典原始行数'' AFTER subject_row_count'
);

CREATE TABLE IF NOT EXISTS cms_subject_setting_raw (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  import_batch_id BIGINT NOT NULL COMMENT '技术导入记录ID，关联cms_cost_import_batch.id',
  row_no INT NOT NULL COMMENT 'Excel行号',
  first_subject_code VARCHAR(64) NOT NULL COMMENT '一级科目编号',
  first_subject_name VARCHAR(120) NOT NULL COMMENT '一级科目名称',
  second_subject_code VARCHAR(64) NOT NULL COMMENT '二级科目编号',
  second_subject_name VARCHAR(120) NOT NULL COMMENT '二级科目名称',
  third_subject_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '三级科目编号',
  third_subject_name VARCHAR(120) DEFAULT NULL COMMENT '三级科目名称',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cms_subject_setting_code (
    first_subject_code,
    second_subject_code,
    third_subject_code
  ),
  KEY idx_cms_subject_setting_first (first_subject_name),
  KEY idx_cms_subject_setting_second (second_subject_code, second_subject_name),
  KEY idx_cms_subject_setting_batch (import_batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS科目设置字典原始数据';

INSERT INTO sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache, menu_type,
   visible, status, perms, icon, create_by, create_time, update_by, update_time, remark)
VALUES
  (40239, 'CMS 科目设置', 40230, 3, '/base/cms-cost/subject-settings', 'pages:CmsSubjectSettingPage', 1, '0', 'C',
   '0', '0', 'cms:cost:list', 'Collection', 'admin', NOW(), '', NOW(),
   'CMS科目设置字典查询入口')
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name),
  parent_id = VALUES(parent_id),
  order_num = VALUES(order_num),
  path = VALUES(path),
  component = VALUES(component),
  is_frame = VALUES(is_frame),
  is_cache = VALUES(is_cache),
  menu_type = VALUES(menu_type),
  visible = VALUES(visible),
  status = VALUES(status),
  perms = VALUES(perms),
  icon = VALUES(icon),
  update_time = NOW(),
  remark = VALUES(remark);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
VALUES
  (1, 40239),
  (10, 40239),
  (11, 40239);

INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT role_id, 40239
FROM sys_role_menu
WHERE menu_id IN (40230);

DROP PROCEDURE IF EXISTS _cms_subject_setting_add_column_if_not_exists;
DROP PROCEDURE IF EXISTS _cms_subject_setting_add_index_if_not_exists;
