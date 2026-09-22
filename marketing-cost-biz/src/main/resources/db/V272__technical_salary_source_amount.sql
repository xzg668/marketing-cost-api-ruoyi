-- 工资参考保存 CMS 原金额，取消用虚构工时/费率凑出该金额的做法。
-- 原字段仅用于读取、复制已提交历史版本，不改写历史记录及其指纹。
ALTER TABLE lp_quote_tech_salary_item
  MODIFY process_code VARCHAR(64) NULL,
  MODIFY process_name VARCHAR(255) NULL,
  MODIFY working_hours DECIMAL(20,8) NULL,
  MODIFY original_time_unit VARCHAR(32) NULL,
  MODIFY standard_hours DECIMAL(20,8) NULL,
  MODIFY standard_time_unit VARCHAR(32) NULL DEFAULT NULL,
  MODIFY conversion_factor DECIMAL(20,8) NULL DEFAULT NULL,
  MODIFY hourly_rate DECIMAL(20,8) NULL,
  MODIFY wage_rate DECIMAL(20,8) NULL,
  MODIFY rate_unit VARCHAR(32) NULL,
  MODIFY person_coefficient DECIMAL(20,8) NULL DEFAULT NULL;
