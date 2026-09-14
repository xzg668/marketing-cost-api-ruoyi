-- =============================================================================
-- V248: 技术资料提交版本数据库级不可变保护
-- -----------------------------------------------------------------------------
-- 1. DRAFT 明细允许维护；非 DRAFT 版本的三类明细禁止 INSERT/UPDATE/DELETE。
-- 2. 版本从 DRAFT 提交后，业务内容、内容指纹和参照快照禁止再修改。
-- 3. 版本状态只允许 DRAFT->SUBMITTED/VOIDED、SUBMITTED->RETURNED/APPROVED/VOIDED。
-- 4. 服务层和 Mapper 条件 SQL 仍保留，触发器是最后一道防绕过保护。
-- =============================================================================

SET NAMES utf8mb4;

DROP PROCEDURE IF EXISTS `sp_quote_tech_assert_draft_version`;

DELIMITER $$

CREATE PROCEDURE `sp_quote_tech_assert_draft_version`(IN p_version_id BIGINT)
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM `lp_quote_tech_data_version`
     WHERE `id` = p_version_id
       AND `version_status` = 'DRAFT'
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_VERSION_IMMUTABLE: only DRAFT details are mutable';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_version_bu_immutable`$$
CREATE TRIGGER `trg_quote_tech_version_bu_immutable`
BEFORE UPDATE ON `lp_quote_tech_data_version`
FOR EACH ROW
BEGIN
  IF NOT (
    (OLD.`version_status` = NEW.`version_status`)
    OR (OLD.`version_status` = 'DRAFT' AND NEW.`version_status` IN ('SUBMITTED', 'VOIDED'))
    OR (OLD.`version_status` = 'SUBMITTED'
        AND NEW.`version_status` IN ('RETURNED', 'APPROVED', 'VOIDED'))
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_VERSION_TRANSITION_INVALID';
  END IF;

  IF OLD.`version_status` <> 'DRAFT' AND NOT (
    NEW.`product_id` <=> OLD.`product_id`
    AND NEW.`version_no` <=> OLD.`version_no`
    AND NEW.`product_model` <=> OLD.`product_model`
    AND NEW.`product_property` <=> OLD.`product_property`
    AND NEW.`new_product_flag` <=> OLD.`new_product_flag`
    AND NEW.`package_total_amount` <=> OLD.`package_total_amount`
    AND NEW.`auxiliary_total_amount` <=> OLD.`auxiliary_total_amount`
    AND NEW.`salary_total_amount` <=> OLD.`salary_total_amount`
    AND NEW.`content_fingerprint` <=> OLD.`content_fingerprint`
    AND NEW.`reference_snapshot_json` <=> OLD.`reference_snapshot_json`
    AND NEW.`created_from_version_id` <=> OLD.`created_from_version_id`
    AND NEW.`submitted_by` <=> OLD.`submitted_by`
    AND NEW.`submitted_at` <=> OLD.`submitted_at`
    AND NEW.`created_by` <=> OLD.`created_by`
    AND NEW.`created_at` <=> OLD.`created_at`
  ) THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_VERSION_IMMUTABLE: submitted content cannot change';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_version_bd_immutable`$$
CREATE TRIGGER `trg_quote_tech_version_bd_immutable`
BEFORE DELETE ON `lp_quote_tech_data_version`
FOR EACH ROW
BEGIN
  IF OLD.`version_status` <> 'DRAFT' THEN
    SIGNAL SQLSTATE '45000'
      SET MESSAGE_TEXT = 'TECH_DATA_VERSION_IMMUTABLE: submitted version cannot be deleted';
  END IF;
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_package_bi_draft`$$
CREATE TRIGGER `trg_quote_tech_package_bi_draft`
BEFORE INSERT ON `lp_quote_tech_package_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`)$$

DROP TRIGGER IF EXISTS `trg_quote_tech_package_bu_draft`$$
CREATE TRIGGER `trg_quote_tech_package_bu_draft`
BEFORE UPDATE ON `lp_quote_tech_package_item`
FOR EACH ROW
BEGIN
  CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`);
  CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`);
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_package_bd_draft`$$
CREATE TRIGGER `trg_quote_tech_package_bd_draft`
BEFORE DELETE ON `lp_quote_tech_package_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`)$$

DROP TRIGGER IF EXISTS `trg_quote_tech_aux_bi_draft`$$
CREATE TRIGGER `trg_quote_tech_aux_bi_draft`
BEFORE INSERT ON `lp_quote_tech_aux_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`)$$

DROP TRIGGER IF EXISTS `trg_quote_tech_aux_bu_draft`$$
CREATE TRIGGER `trg_quote_tech_aux_bu_draft`
BEFORE UPDATE ON `lp_quote_tech_aux_item`
FOR EACH ROW
BEGIN
  CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`);
  CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`);
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_aux_bd_draft`$$
CREATE TRIGGER `trg_quote_tech_aux_bd_draft`
BEFORE DELETE ON `lp_quote_tech_aux_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`)$$

DROP TRIGGER IF EXISTS `trg_quote_tech_salary_bi_draft`$$
CREATE TRIGGER `trg_quote_tech_salary_bi_draft`
BEFORE INSERT ON `lp_quote_tech_salary_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`)$$

DROP TRIGGER IF EXISTS `trg_quote_tech_salary_bu_draft`$$
CREATE TRIGGER `trg_quote_tech_salary_bu_draft`
BEFORE UPDATE ON `lp_quote_tech_salary_item`
FOR EACH ROW
BEGIN
  CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`);
  CALL `sp_quote_tech_assert_draft_version`(NEW.`version_id`);
END$$

DROP TRIGGER IF EXISTS `trg_quote_tech_salary_bd_draft`$$
CREATE TRIGGER `trg_quote_tech_salary_bd_draft`
BEFORE DELETE ON `lp_quote_tech_salary_item`
FOR EACH ROW CALL `sp_quote_tech_assert_draft_version`(OLD.`version_id`)$$

DELIMITER ;
