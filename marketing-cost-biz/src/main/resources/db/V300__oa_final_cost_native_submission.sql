-- I07 改为一次原生 submitRequest；保留成本快照、原报文及实际操作人。
-- 移除旧模拟协议的节点/审批人/步骤字段。部署前停止旧版 OA 出站进程。
-- 有未核实的旧步骤或非空专用元数据时拒绝删除，须先归档核实。
DROP PROCEDURE IF EXISTS migrate_v300_oa_final;
DELIMITER $$
CREATE PROCEDURE migrate_v300_oa_final()
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_quote_final_submission' AND column_name='current_step') THEN
    IF EXISTS (SELECT 1 FROM lp_quote_final_submission
        WHERE status IN ('PENDING','UNKNOWN') OR external_flow_id IS NOT NULL
          OR approver_external_id IS NOT NULL OR step_attempt<>0
          OR current_step<>'QUOTE_COST_SUBMIT') THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V300: verify/archive legacy final-submission steps before migration';
    END IF;
    ALTER TABLE lp_quote_final_submission DROP COLUMN external_flow_id,
      DROP COLUMN approver_external_id,DROP COLUMN current_step,DROP COLUMN step_attempt;
  END IF;
END$$
DELIMITER ;
CALL migrate_v300_oa_final();
DROP PROCEDURE migrate_v300_oa_final;
