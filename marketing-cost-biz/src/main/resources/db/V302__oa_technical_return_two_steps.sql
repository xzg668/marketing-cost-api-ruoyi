-- 停止旧后端和 worker 后执行。原 OA 单据的退回节点由 I01 提供，不推测历史单节点。
ALTER TABLE lp_oa_quote_document
  ADD COLUMN reject_to_node_id VARCHAR(128) NULL COMMENT 'I01原样接收的RejectToNodeid';

ALTER TABLE lp_oa_technical_batch
  ADD COLUMN return_step VARCHAR(16) NULL COMMENT 'I05当前步骤：PEOPLE回传人员、REJECT退回流程',
  ADD COLUMN return_attempt INT NOT NULL DEFAULT 0 COMMENT 'I05已占用的发送次数，用于重试并发校验',
  ADD COLUMN reject_request_json JSON NULL COMMENT 'I05第二步固定退回报文',
  ADD COLUMN people_result_json JSON NULL COMMENT 'I05第一步回传人员的回执',
  DROP CHECK ck_oa_technical_batch_status,
  ADD CONSTRAINT ck_oa_technical_batch_status CHECK
    (status IN ('PREPARED','SENDING','OA_ACCEPTED','SUCCESS','REJECTED','NOT_SENT','UNKNOWN','REJECT_READY','RETURN_FAILED')),
  ADD CONSTRAINT ck_oa_technical_return_step CHECK
    (return_step IS NULL OR (operation='I05' AND return_step IN ('PEOPLE','REJECT')));

-- 旧版单次发送不能被新版当作两步成功，也不能升级后自动补发未知的退回请求。
UPDATE lp_oa_technical_batch SET people_result_json=result_json,status='UNKNOWN',
  result_json=JSON_OBJECT('status','UNKNOWN','errorCode','LEGACY_RETURN_UNCONFIRMED',
    'message','升级前退回记录未完成两步确认，请核实OA流程；未自动补发',
    'requestId',JSON_UNQUOTE(JSON_EXTRACT(request_json,'$.requestId')),'durationMs',0),
  updated_at=NOW(3)
WHERE operation='I05' AND status IN ('PREPARED','SENDING','OA_ACCEPTED');
