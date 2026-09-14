-- =============================================================================
-- V253: 成本版本绑定唯一的审核生效技术资料输入
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `lp_quote_cost_run_version`
  ADD COLUMN `tech_data_version_id` BIGINT DEFAULT NULL
    COMMENT '本次成本读取的lp_quote_tech_data_version.id',
  ADD COLUMN `tech_data_version_no` INT DEFAULT NULL
    COMMENT '本次成本读取的技术版本号' AFTER `tech_data_version_id`,
  ADD COLUMN `tech_data_source` VARCHAR(64) DEFAULT NULL
    COMMENT '技术取数来源：QUOTE_TECH_EFFECTIVE_VERSION' AFTER `tech_data_version_no`,
  ADD COLUMN `tech_data_input_json` JSON DEFAULT NULL
    COMMENT '包装/辅料/工资的不可变计算输入快照' AFTER `tech_data_source`,
  ADD COLUMN `tech_data_retrieved_at` DATETIME DEFAULT NULL
    COMMENT '技术资料实际取数时间' AFTER `tech_data_input_json`,
  ADD KEY `idx_quote_cost_run_tech_version` (`tech_data_version_id`),
  ADD CONSTRAINT `fk_quote_cost_run_tech_version`
    FOREIGN KEY (`tech_data_version_id`)
    REFERENCES `lp_quote_tech_data_version` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_quote_cost_run_tech_trace` CHECK (
    (`tech_data_version_id` IS NULL
      AND `tech_data_version_no` IS NULL
      AND `tech_data_source` IS NULL
      AND `tech_data_input_json` IS NULL
      AND `tech_data_retrieved_at` IS NULL)
    OR
    (`tech_data_version_id` IS NOT NULL
      AND `tech_data_version_no` > 0
      AND `tech_data_source` = 'QUOTE_TECH_EFFECTIVE_VERSION'
      AND `tech_data_input_json` IS NOT NULL
      AND `tech_data_retrieved_at` IS NOT NULL)
  );
