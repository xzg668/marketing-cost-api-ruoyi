-- I04/I08 改为同步四状态通知；旧 eventId 去重表已无运行时用途。
-- 2026-09-26 本地检查：旧事件表 0 行、旧接收箱待处理 0 行。
-- 保留共用接口原文、任务提交快照和历史迁移；非空环境先归档核查，避免删除历史证据。
DROP PROCEDURE IF EXISTS migrate_v299_oa_event;
DELIMITER $$
CREATE PROCEDURE migrate_v299_oa_event()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='lp_oa_workflow_event') THEN
    IF EXISTS (SELECT 1 FROM lp_oa_workflow_event LIMIT 1) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V299: archive and verify non-empty lp_oa_workflow_event before dropping';
    END IF;
    DROP TABLE lp_oa_workflow_event;
  END IF;
END$$
DELIMITER ;
CALL migrate_v299_oa_event();
DROP PROCEDURE migrate_v299_oa_event;
