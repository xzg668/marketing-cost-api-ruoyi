ALTER TABLE `lp_quote_tech_salary_item`
  ADD COLUMN `wage_rate` DECIMAL(20,8) NULL COMMENT '原始工资率' AFTER `conversion_factor`,
  ADD COLUMN `rate_unit` VARCHAR(32) NULL COMMENT '原始工资率计价单位' AFTER `wage_rate`,
  ADD COLUMN `person_coefficient` DECIMAL(20,8) NOT NULL DEFAULT 1 COMMENT '人数或人工系数' AFTER `hourly_rate`;

UPDATE `lp_quote_tech_salary_item`
   SET `wage_rate` = `hourly_rate`
 WHERE `wage_rate` IS NULL;

UPDATE `lp_quote_tech_salary_item`
   SET `rate_unit` = '元/小时'
 WHERE `rate_unit` IS NULL OR TRIM(`rate_unit`) = '';

ALTER TABLE `lp_quote_tech_salary_item`
  MODIFY COLUMN `wage_rate` DECIMAL(20,8) NOT NULL COMMENT '原始工资率',
  MODIFY COLUMN `rate_unit` VARCHAR(32) NOT NULL COMMENT '原始工资率计价单位',
  ADD CONSTRAINT `ck_quote_tech_salary_person_coefficient`
    CHECK (`person_coefficient` > 0);
