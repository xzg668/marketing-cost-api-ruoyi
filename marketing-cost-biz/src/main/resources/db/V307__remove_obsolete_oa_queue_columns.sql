-- 旧 OA 接收/发送队列已移除；消息原文、请求幂等及回执仍由当前业务使用。
-- 先部署不再读写租约字段的应用并停止旧实例，再经删列授权执行本迁移。
-- 存在租约或非默认重试时间时拒绝删除，须先核查、归档其历史用途。
DROP PROCEDURE IF EXISTS migrate_v307_oa_queue;
DELIMITER $$
CREATE PROCEDURE migrate_v307_oa_queue()
cleanup: BEGIN
  DECLARE obsolete_column_count INT;

  SELECT COUNT(*) INTO obsolete_column_count
  FROM information_schema.columns
  WHERE table_schema=DATABASE() AND table_name='lp_oa_integration_message'
    AND column_name IN ('lease_token','lease_until','next_attempt_at');

  IF obsolete_column_count=0 THEN
    LEAVE cleanup;
  END IF;
  IF obsolete_column_count<>3 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V307: inspect partially removed OA queue columns before migration';
  END IF;
  IF EXISTS (
    SELECT 1 FROM lp_oa_integration_message
    WHERE lease_token IS NOT NULL OR lease_until IS NOT NULL OR next_attempt_at<>received_at
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V307: inspect and archive existing OA lease or retry data before dropping columns';
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='lp_oa_integration_message'
      AND index_name='idx_oa_message_poll'
  ) THEN
    ALTER TABLE lp_oa_integration_message DROP INDEX idx_oa_message_poll;
  END IF;
  ALTER TABLE lp_oa_integration_message
    DROP COLUMN lease_token,
    DROP COLUMN lease_until,
    DROP COLUMN next_attempt_at;
END$$
DELIMITER ;
CALL migrate_v307_oa_queue();
DROP PROCEDURE migrate_v307_oa_queue;
