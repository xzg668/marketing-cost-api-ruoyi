-- V260：补录工作台产品任务、九模块版本和统一发送快照基础。
-- 先在演练库执行。本迁移不生成 OA 审批、不删除旧历史、不改 new_product_flag 含义。
-- 活动旧任务必须恰好关联一个活动产品；聚合任务需先制定逐产品迁移，不自动拆历史审批。
-- 本文件只执行一次；存在 content_schema_version 表示需核对执行记录，禁止盲目重跑。
SET NAMES utf8mb4;
DELIMITER $$
CREATE PROCEDURE sp_quote_tech_v260_preflight()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='lp_quote_tech_product'
        AND COLUMN_NAME='content_schema_version') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V260 already started/applied; verify migration state';
  END IF;
  IF EXISTS (SELECT t.id FROM lp_quote_tech_task t
      LEFT JOIN lp_quote_tech_product p ON p.task_id=t.id AND p.active_flag=1
      WHERE t.active_flag=1 GROUP BY t.id HAVING COUNT(p.id)<>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V260 requires an explicit migration for active aggregate/orphan tasks';
  END IF;
  IF EXISTS (SELECT 1 FROM lp_quote_tech_product p JOIN lp_quote_tech_task t ON t.id=p.task_id
      WHERE p.active_flag=1 AND (t.active_flag<>1 OR p.accounting_month<>t.accounting_month)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V260 active product/task context mismatch';
  END IF;
END$$
CALL sp_quote_tech_v260_preflight()$$
DROP PROCEDURE sp_quote_tech_v260_preflight$$
DELIMITER ;

ALTER TABLE lp_quote_tech_task
  ADD COLUMN oa_form_item_id BIGINT NULL AFTER oa_form_id,
  DROP CHECK ck_quote_tech_task_active_lock,
  DROP CHECK ck_quote_tech_task_status;
UPDATE lp_quote_tech_task t JOIN
  (SELECT task_id, MIN(oa_form_item_id) item_id FROM lp_quote_tech_product
   WHERE active_flag=1 GROUP BY task_id HAVING COUNT(*)=1) p ON p.task_id=t.id
SET t.oa_form_item_id=p.item_id WHERE t.active_flag=1;
UPDATE lp_quote_tech_task t JOIN
  (SELECT task_id, MIN(oa_form_item_id) item_id FROM lp_quote_tech_product
   GROUP BY task_id HAVING COUNT(*)=1) p ON p.task_id=t.id
SET t.oa_form_item_id=p.item_id WHERE t.active_flag=0;
UPDATE lp_quote_tech_task SET active_lock_key=CONCAT('ITEM:',oa_form_item_id,':MONTH:',accounting_month)
WHERE active_flag=1;
ALTER TABLE lp_quote_tech_task
  ADD CONSTRAINT ck_quote_tech_task_active_lock CHECK (
    (active_flag=1 AND oa_form_item_id IS NOT NULL AND active_lock_key IS NOT NULL
     AND active_lock_key=CONCAT('ITEM:',oa_form_item_id,':MONTH:',accounting_month))
    OR (active_flag=0 AND active_lock_key IS NULL)),
  ADD CONSTRAINT ck_quote_tech_task_status CHECK (task_status IN
    ('PENDING','IN_PROGRESS','PREPARED','SUBMITTED','PARTIALLY_RETURNED','APPROVED','CANCELLED'));

ALTER TABLE lp_quote_tech_product
  ADD COLUMN content_schema_version INT NOT NULL DEFAULT 1,
  ADD COLUMN active_task_id BIGINT GENERATED ALWAYS AS (CASE WHEN active_flag=1 THEN task_id ELSE NULL END) STORED,
  ADD UNIQUE KEY uk_quote_tech_product_active_task (active_task_id),
  ADD UNIQUE KEY uk_quote_tech_product_id_task (id,task_id),
  ADD CONSTRAINT ck_quote_tech_product_schema CHECK (content_schema_version IN (1,2)),
  DROP CHECK ck_quote_tech_product_status,
  ADD CONSTRAINT ck_quote_tech_product_status CHECK (product_status IN
    ('PENDING','EDITING','READY','PREPARED','SUBMITTED','RETURNED','APPROVED'));

ALTER TABLE lp_quote_tech_module
  ADD COLUMN source_availability VARCHAR(16) NULL COMMENT '旧模块无来源检查证据时保留NULL',
  ADD COLUMN source_reference VARCHAR(512) NULL,
  ADD COLUMN source_checked_at DATETIME(6) NULL,
  DROP CHECK ck_quote_tech_module_type,
  DROP CHECK ck_quote_tech_module_status,
  ADD CONSTRAINT ck_quote_tech_module_status CHECK (module_status IN
    ('NOT_REQUIRED','PENDING','EDITING','READY','FROZEN','SUBMITTED','RETURNED','APPROVED')),
  DROP CHECK ck_quote_tech_module_required,
  ADD CONSTRAINT ck_quote_tech_module_required CHECK
    (required_flag IN (0,1) AND (required_flag=1
     OR (module_status='NOT_REQUIRED' AND COALESCE(source_availability,'AVAILABLE')='AVAILABLE')
     OR (source_availability IN ('UNCONFIRMED','ERROR') AND module_status IN ('PENDING','EDITING','READY')))),
  ADD CONSTRAINT ck_quote_tech_module_type CHECK (module_type IN
    ('PROFILE','DRAWING_BOM','MANUFACTURING','PACKAGE','AUXILIARY','SOLDER','SALARY','NET_LOSS','PRICE')),
  ADD CONSTRAINT ck_quote_tech_module_source CHECK (source_availability IS NULL OR source_availability IN
    ('AVAILABLE','MISSING','UNCONFIRMED','ERROR')),
  ADD CONSTRAINT ck_quote_tech_module_source_time CHECK
    (source_availability IS NULL OR source_availability='UNCONFIRMED' OR source_checked_at IS NOT NULL),
  ADD CONSTRAINT ck_quote_tech_module_source_requirement CHECK
    (source_availability IS NULL OR (source_availability='MISSING' AND required_flag=1)
     OR (source_availability<>'MISSING' AND required_flag=0));

ALTER TABLE lp_quote_tech_data_version
  ADD COLUMN content_schema_version INT NOT NULL DEFAULT 1,
  ADD COLUMN product_fees_json JSON NULL,
  ADD COLUMN drawing_bom_json JSON NULL,
  ADD COLUMN manufacturing_json JSON NULL,
  ADD COLUMN packaging_json JSON NULL,
  ADD COLUMN solder_items_json JSON NULL,
  ADD COLUMN net_loss_json JSON NULL,
  ADD COLUMN price_items_json JSON NULL,
  ADD COLUMN source_facts_json JSON NULL,
  ADD UNIQUE KEY uk_quote_tech_version_id_product (id,product_id),
  DROP CHECK ck_quote_tech_version_status,
  ADD CONSTRAINT ck_quote_tech_version_status CHECK (version_status IN
    ('DRAFT','FROZEN','SUBMITTED','RETURNED','APPROVED','VOIDED')),
  ADD CONSTRAINT ck_quote_tech_version_schema CHECK (content_schema_version IN (1,2)),
  ADD CONSTRAINT ck_quote_tech_version_legacy_content CHECK (content_schema_version=2 OR
    (product_fees_json IS NULL AND drawing_bom_json IS NULL AND manufacturing_json IS NULL
     AND packaging_json IS NULL AND solder_items_json IS NULL AND net_loss_json IS NULL
     AND price_items_json IS NULL AND source_facts_json IS NULL)),
  ADD CONSTRAINT ck_quote_tech_version_frozen_snapshot CHECK
    (content_schema_version=1 OR version_status IN ('DRAFT','VOIDED') OR
     (content_fingerprint IS NOT NULL AND reference_snapshot_json IS NOT NULL AND source_facts_json IS NOT NULL));

CREATE TABLE lp_quote_tech_submission (
  id BIGINT NOT NULL AUTO_INCREMENT,
  task_id BIGINT NOT NULL,
  product_id BIGINT NOT NULL,
  technical_version_id BIGINT NOT NULL,
  submission_round INT NOT NULL,
  request_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  expected_task_version INT NOT NULL,
  expected_product_version INT NOT NULL,
  content_schema_version INT NOT NULL,
  content_fingerprint CHAR(64) COLLATE utf8mb4_bin NOT NULL,
  content_snapshot_json JSON NOT NULL,
  summary_json JSON NOT NULL,
  previous_submission_id BIGINT NULL,
  assignee_user_id BIGINT NOT NULL,
  submitted_by BIGINT NOT NULL,
  prepared_at DATETIME(6) NOT NULL,
  submission_status VARCHAR(16) NOT NULL DEFAULT 'PREPARED',
  external_flow_id VARCHAR(128) NULL,
  external_submission_id VARCHAR(128) NULL,
  row_version INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  UNIQUE KEY uk_quote_tech_submission_round (task_id,submission_round),
  UNIQUE KEY uk_quote_tech_submission_request (task_id,request_id),
  UNIQUE KEY uk_quote_tech_submission_version (technical_version_id),
  UNIQUE KEY uk_quote_tech_submission_id_task (id,task_id),
  CONSTRAINT fk_quote_tech_submission_product FOREIGN KEY (product_id,task_id)
    REFERENCES lp_quote_tech_product(id,task_id),
  CONSTRAINT fk_quote_tech_submission_version FOREIGN KEY (technical_version_id,product_id)
    REFERENCES lp_quote_tech_data_version(id,product_id),
  CONSTRAINT fk_quote_tech_submission_previous FOREIGN KEY (previous_submission_id,task_id)
    REFERENCES lp_quote_tech_submission(id,task_id),
  CONSTRAINT ck_quote_tech_submission_status CHECK (submission_status IN
    ('PREPARED','SENDING','UNKNOWN','SENT','RETURNED','APPROVED','FAILED')),
  CONSTRAINT ck_quote_tech_submission_schema CHECK (content_schema_version=2),
  CONSTRAINT ck_quote_tech_submission_version CHECK
    (submission_round>0 AND row_version>=0 AND expected_task_version>=0 AND expected_product_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整产品不可变发送快照及独立传输状态';

DELIMITER $$
DROP TRIGGER IF EXISTS trg_quote_tech_version_bu_immutable$$
CREATE TRIGGER `trg_quote_tech_version_bu_immutable`
BEFORE UPDATE ON `lp_quote_tech_data_version`
FOR EACH ROW
BEGIN
  IF NOT (
    (OLD.`version_status` = NEW.`version_status`)
    OR (OLD.`version_status` = 'DRAFT' AND NEW.`version_status` = 'VOIDED')
    OR (OLD.`version_status` = 'DRAFT' AND OLD.`content_schema_version` = 1 AND NEW.`version_status` = 'SUBMITTED')
    OR (OLD.`version_status` = 'DRAFT' AND OLD.`content_schema_version` = 2 AND NEW.`version_status` = 'FROZEN')
    OR (OLD.`version_status` = 'FROZEN' AND NEW.`version_status` IN ('SUBMITTED', 'VOIDED'))
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
    AND NEW.`content_schema_version` <=> OLD.`content_schema_version`
    AND NEW.`product_fees_json` <=> OLD.`product_fees_json`
    AND NEW.`drawing_bom_json` <=> OLD.`drawing_bom_json`
    AND NEW.`manufacturing_json` <=> OLD.`manufacturing_json`
    AND NEW.`packaging_json` <=> OLD.`packaging_json`
    AND NEW.`solder_items_json` <=> OLD.`solder_items_json`
    AND NEW.`net_loss_json` <=> OLD.`net_loss_json`
    AND NEW.`price_items_json` <=> OLD.`price_items_json`
    AND NEW.`source_facts_json` <=> OLD.`source_facts_json`
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

CREATE TRIGGER trg_quote_tech_task_bu_identity BEFORE UPDATE ON lp_quote_tech_task
FOR EACH ROW
BEGIN
  IF OLD.oa_form_item_id IS NOT NULL AND NOT
    (NEW.oa_form_item_id <=> OLD.oa_form_item_id AND NEW.oa_form_id <=> OLD.oa_form_id
     AND NEW.oa_no <=> OLD.oa_no AND NEW.accounting_month <=> OLD.accounting_month
     AND NEW.business_unit_type <=> OLD.business_unit_type AND NEW.applicable_org_code <=> OLD.applicable_org_code) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_TASK_IDENTITY_IMMUTABLE';
  END IF;
END$$
CREATE PROCEDURE sp_quote_tech_assert_product_context(
  IN p_task BIGINT, IN p_item BIGINT,
  IN p_month CHAR(7) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci)
BEGIN
  IF NOT EXISTS (SELECT 1 FROM lp_quote_tech_task WHERE id=p_task AND active_flag=1
      AND oa_form_item_id=p_item AND accounting_month=p_month) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_PRODUCT_TASK_CONTEXT_MISMATCH';
  END IF;
END$$
CREATE TRIGGER trg_quote_tech_product_bi_context BEFORE INSERT ON lp_quote_tech_product
FOR EACH ROW
BEGIN
  IF NEW.active_flag=1 THEN
    CALL sp_quote_tech_assert_product_context(NEW.task_id,NEW.oa_form_item_id,NEW.accounting_month);
  END IF;
END$$
CREATE TRIGGER trg_quote_tech_product_bu_context BEFORE UPDATE ON lp_quote_tech_product
FOR EACH ROW
BEGIN
  IF NOT (NEW.task_id <=> OLD.task_id AND NEW.oa_form_item_id <=> OLD.oa_form_item_id
      AND NEW.accounting_month <=> OLD.accounting_month AND NEW.content_schema_version <=> OLD.content_schema_version) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_PRODUCT_IDENTITY_IMMUTABLE';
  END IF;
  IF NEW.active_flag=1 THEN
    CALL sp_quote_tech_assert_product_context(NEW.task_id,NEW.oa_form_item_id,NEW.accounting_month);
  END IF;
END$$
CREATE TRIGGER trg_quote_tech_submission_bi_snapshot BEFORE INSERT ON lp_quote_tech_submission
FOR EACH ROW
BEGIN
  IF NEW.submission_status<>'PREPARED' OR NOT EXISTS
    (SELECT 1 FROM lp_quote_tech_data_version v JOIN lp_quote_tech_product p ON p.id=v.product_id
     WHERE v.id=NEW.technical_version_id AND v.product_id=NEW.product_id AND p.task_id=NEW.task_id
       AND v.version_status='FROZEN' AND v.content_schema_version=2
       AND v.content_fingerprint=NEW.content_fingerprint) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_SUBMISSION_REQUIRES_FROZEN_VERSION';
  END IF;
END$$
CREATE TRIGGER trg_quote_tech_submission_bd_immutable BEFORE DELETE ON lp_quote_tech_submission
FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_SUBMISSION_SNAPSHOT_IMMUTABLE'$$
CREATE TRIGGER trg_quote_tech_submission_bu_immutable BEFORE UPDATE ON lp_quote_tech_submission
FOR EACH ROW
BEGIN
  IF NOT (
NEW.task_id <=> OLD.task_id
    AND NEW.product_id <=> OLD.product_id
    AND NEW.technical_version_id <=> OLD.technical_version_id
    AND NEW.submission_round <=> OLD.submission_round
    AND NEW.request_id <=> OLD.request_id
    AND NEW.expected_task_version <=> OLD.expected_task_version
    AND NEW.expected_product_version <=> OLD.expected_product_version
    AND NEW.content_schema_version <=> OLD.content_schema_version
    AND NEW.content_fingerprint <=> OLD.content_fingerprint
    AND NEW.content_snapshot_json <=> OLD.content_snapshot_json
    AND NEW.summary_json <=> OLD.summary_json
    AND NEW.previous_submission_id <=> OLD.previous_submission_id
    AND NEW.assignee_user_id <=> OLD.assignee_user_id
    AND NEW.submitted_by <=> OLD.submitted_by
    AND NEW.prepared_at <=> OLD.prepared_at) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_SUBMISSION_SNAPSHOT_IMMUTABLE';
  END IF;
  IF NEW.submission_status<>OLD.submission_status AND NOT
    ((OLD.submission_status='PREPARED' AND NEW.submission_status IN ('SENDING','FAILED'))
     OR (OLD.submission_status='SENDING' AND NEW.submission_status IN ('UNKNOWN','SENT','FAILED'))
     OR (OLD.submission_status='UNKNOWN' AND NEW.submission_status IN ('SENT','FAILED'))
     OR (OLD.submission_status='FAILED' AND NEW.submission_status='SENDING')
     OR (OLD.submission_status='SENT' AND NEW.submission_status IN ('RETURNED','APPROVED'))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TECH_SUBMISSION_TRANSITION_INVALID';
  END IF;
END$$
DELIMITER ;
