-- 报价员检查资料和核算属于同一办理阶段；只在 I07 确认成本时向 OA 提交。
-- 保留 V296 历史发送记录用于核对回执，新代码不再读写该表。
INSERT IGNORE INTO lp_oa_workflow_state(source_system,environment,workflow_request_id,
    form_version,observed_form_version,active_work_items_json)
SELECT DISTINCT d.source_system,d.environment,d.external_document_id,d.source_version,d.source_version,JSON_ARRAY()
FROM lp_oa_quote_document d JOIN lp_oa_material_confirmation c
  ON c.oa_form_id=d.oa_form_id AND c.form_version=d.source_version
WHERE c.status IN ('SENDING','UNKNOWN');

-- 升级不能把已发出但结果未确认的旧请求当作从未发送；仅冻结当前办理轮次。
UPDATE lp_oa_workflow_state s JOIN lp_oa_quote_document d
  ON s.source_system=d.source_system AND s.environment=d.environment AND s.workflow_request_id=d.external_document_id
JOIN lp_oa_material_confirmation c ON c.oa_form_id=d.oa_form_id AND c.form_version=d.source_version
SET s.sync_error=COALESCE(s.sync_error,'升级前资料提交结果未确认，请先核实 OA 当前流程状态')
WHERE c.status IN ('SENDING','UNKNOWN') AND (
  (s.applied_version=0 AND c.material_work_item_id=CONCAT('LOCAL-INITIAL:',d.oa_form_id,':',d.source_version))
  OR (s.state='MATERIAL_REVIEW' AND JSON_CONTAINS(s.active_work_items_json,
      JSON_OBJECT('workItemId',c.material_work_item_id))));

-- 保留 workItemId，已有 I05 退回请求仍可按原办理轮次核对回执。
UPDATE lp_oa_workflow_state SET state='COSTING',
    active_work_items_json=REPLACE(CAST(active_work_items_json AS CHAR),
      '"nodeRole": "MATERIAL"','"nodeRole": "COSTING"')
WHERE state='MATERIAL_REVIEW';
