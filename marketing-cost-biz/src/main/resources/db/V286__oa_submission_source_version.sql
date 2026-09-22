-- 冻结提交记录采用的需求版本，避免新需求到达后把旧资料的审批误用于新需求。
ALTER TABLE lp_quote_tech_submission ADD COLUMN source_form_version BIGINT NULL;
ALTER TABLE lp_quote_final_submission ADD COLUMN source_form_version BIGINT NULL;
DELIMITER $$
CREATE TRIGGER trg_tech_submission_source_version BEFORE INSERT ON lp_quote_tech_submission
FOR EACH ROW
BEGIN
 SET NEW.source_form_version=(SELECT MAX(d.source_version) FROM lp_oa_quote_document d
   JOIN lp_quote_tech_task t ON t.oa_form_id=d.oa_form_id WHERE t.id=NEW.task_id);
END$$
CREATE TRIGGER trg_tech_submission_source_immutable BEFORE UPDATE ON lp_quote_tech_submission
FOR EACH ROW
BEGIN
 IF NOT(NEW.source_form_version <=> OLD.source_form_version) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUBMISSION_SOURCE_VERSION_IMMUTABLE';
 END IF;
END$$
CREATE TRIGGER trg_final_submission_source_version BEFORE INSERT ON lp_quote_final_submission
FOR EACH ROW
BEGIN
 SET NEW.source_form_version=(SELECT MAX(source_version) FROM lp_oa_quote_document WHERE oa_form_id=NEW.oa_form_id);
END$$
CREATE TRIGGER trg_final_submission_source_immutable BEFORE UPDATE ON lp_quote_final_submission
FOR EACH ROW
BEGIN
 IF NOT(NEW.source_form_version <=> OLD.source_form_version) THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SUBMISSION_SOURCE_VERSION_IMMUTABLE';
 END IF;
END$$
DELIMITER ;
