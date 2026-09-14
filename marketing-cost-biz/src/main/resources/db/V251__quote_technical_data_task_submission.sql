ALTER TABLE `lp_quote_tech_task`
  ADD COLUMN `submission_idempotency_key` VARCHAR(128) NULL
    COMMENT '当前审核轮次提交幂等键' AFTER `review_status`,
  ADD COLUMN `submission_fingerprint` CHAR(64) NULL
    COMMENT '当前审核轮次全部产品版本指纹' AFTER `submission_idempotency_key`,
  ADD KEY `idx_quote_tech_task_submission_key` (`id`, `submission_idempotency_key`);

-- 已提交审核期间，产品来源身份和模块录入内容只能通过后续审核/退回状态机改变。
-- 这里不冻结审核状态及生效指针，给 V2 退回和最终生效保留合法迁移空间。
DELIMITER $$

DROP TRIGGER IF EXISTS `trg_quote_tech_product_bu_submitted_guard`$$
CREATE TRIGGER `trg_quote_tech_product_bu_submitted_guard`
BEFORE UPDATE ON `lp_quote_tech_product`
FOR EACH ROW
BEGIN
  IF EXISTS (
    SELECT 1 FROM `lp_quote_tech_task`
     WHERE `id` = OLD.`task_id` AND `task_status` IN ('SUBMITTED','APPROVED')
  ) AND NOT (
    NEW.`task_id` <=> OLD.`task_id`
    AND NEW.`oa_form_item_id` <=> OLD.`oa_form_item_id`
    AND NEW.`level_no` <=> OLD.`level_no`
    AND NEW.`material_no` <=> OLD.`material_no`
    AND NEW.`product_name` <=> OLD.`product_name`
    AND NEW.`source_model` <=> OLD.`source_model`
    AND NEW.`source_spec` <=> OLD.`source_spec`
    AND NEW.`quote_no` <=> OLD.`quote_no`
    AND NEW.`accounting_month` <=> OLD.`accounting_month`
    AND NEW.`source_snapshot_json` <=> OLD.`source_snapshot_json`
    AND NEW.`source_fingerprint` <=> OLD.`source_fingerprint`
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_PRODUCT_IMMUTABLE: submitted product identity cannot change';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_module_bu_submitted_guard`$$
CREATE TRIGGER `trg_quote_tech_module_bu_submitted_guard`
BEFORE UPDATE ON `lp_quote_tech_module`
FOR EACH ROW
BEGIN
  IF EXISTS (
    SELECT 1
      FROM `lp_quote_tech_product` p
      JOIN `lp_quote_tech_task` t ON t.`id` = p.`task_id`
     WHERE p.`id` = OLD.`product_id`
       AND t.`task_status` IN ('SUBMITTED','APPROVED')
  ) AND NOT (
    NEW.`product_id` <=> OLD.`product_id`
    AND NEW.`module_type` <=> OLD.`module_type`
    AND NEW.`required_flag` <=> OLD.`required_flag`
    AND NEW.`requirement_reason_code` <=> OLD.`requirement_reason_code`
    AND NEW.`requirement_reason` <=> OLD.`requirement_reason`
    AND NEW.`entry_mode` <=> OLD.`entry_mode`
    AND NEW.`current_version_id` <=> OLD.`current_version_id`
    AND NEW.`reference_source_type` <=> OLD.`reference_source_type`
    AND NEW.`reference_source_id` <=> OLD.`reference_source_id`
    AND NEW.`reference_source_version` <=> OLD.`reference_source_version`
    AND NEW.`reference_fingerprint` <=> OLD.`reference_fingerprint`
    AND NEW.`reference_snapshot_json` <=> OLD.`reference_snapshot_json`
    AND NEW.`referenced_at` <=> OLD.`referenced_at`
    AND NEW.`last_validation_code` <=> OLD.`last_validation_code`
    AND NEW.`last_validation_message` <=> OLD.`last_validation_message`
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_MODULE_IMMUTABLE: submitted module content cannot change';
  END IF;
END$$

DELIMITER ;
