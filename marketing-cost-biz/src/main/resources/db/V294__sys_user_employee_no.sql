-- 工号由账号管理员维护；已有账号允许暂不填写，保留工号中的前导零。
SET NAMES utf8mb4;

SET @v294_add_employee_no = IF(
    EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sys_user' AND COLUMN_NAME = 'employee_no'
    ),
    'SELECT 1',
    'ALTER TABLE sys_user ADD COLUMN employee_no VARCHAR(64) NULL COMMENT ''员工工号'' AFTER user_name'
);
PREPARE v294_stmt FROM @v294_add_employee_no;
EXECUTE v294_stmt;
DEALLOCATE PREPARE v294_stmt;
