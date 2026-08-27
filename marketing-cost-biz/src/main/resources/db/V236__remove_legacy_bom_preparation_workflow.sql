-- 报价 BOM 只保留统一的系统内协作主链；移除旧独立准备页、补录任务和未接通的 OA 集成壳。
SET NAMES utf8mb4;

DELETE FROM sys_role_menu
WHERE menu_id IN (
  SELECT menu_id FROM sys_menu
  WHERE component IN (
    'ingest/quote-request-products/bom/index',
    'pages:BomSupplementTaskPage',
    'pages:BomPreparationPlaceholderPage'
  )
);

DELETE FROM sys_menu
WHERE component IN (
  'ingest/quote-request-products/bom/index',
  'pages:BomSupplementTaskPage',
  'pages:BomPreparationPlaceholderPage'
);

DELETE FROM lp_collaboration_token
WHERE token_type IN ('bom-supplement', 'price-supplement');

UPDATE sys_role
SET role_name = '技术协作者', role_key = 'technical_collaborator',
    remark = '系统内技术协作任务，可与其他角色叠加'
WHERE LOWER(role_key) = 'oa_collaborator';

-- 已产生的内部协作轨迹先迁入通用业务日志，避免删除未启用的 OA Outbox 时丢失页面时间线。
INSERT INTO lp_business_change_log (
  biz_domain, biz_type, biz_id, task_id, field_name, field_label,
  after_value, change_reason, changed_at, change_source, request_id, created_at
)
SELECT
  'QUOTE_COLLABORATION', 'PRODUCT_TASK_EVENT', aggregate_id, aggregate_id,
  event_type, '协作任务状态',
  JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.data.statusCode')),
  CASE event_type
    WHEN 'TECH_TASK_CREATED' THEN '技术协作任务已创建'
    WHEN 'TECH_TASK_LINKED' THEN '当前报价已关联正在处理的技术任务'
    WHEN 'TECH_TASK_UPDATED' THEN '技术处理状态已更新'
    WHEN 'TECH_TASK_COMPLETED' THEN '技术补录已提交'
    WHEN 'TECH_TASK_REOPENED' THEN '任务已退回技术修改'
    WHEN 'APPROVED_RESULT_REUSED' THEN '当前报价已复用审核通过的结果'
    WHEN 'COSTING_STARTED' THEN '已进入成本核算'
    WHEN 'COSTING_COMPLETED' THEN '成本核算已完成'
    WHEN 'COLLABORATION_CANCELLED' THEN '协作任务已取消'
    ELSE '协作任务状态已更新'
  END,
  occurred_at, 'SYSTEM', idempotency_key, created_at
FROM lp_integration_outbox source
WHERE aggregate_type = 'PRODUCT_TASK';

ALTER TABLE lp_quote_bom_preparation_record
  DROP COLUMN task_id,
  DROP COLUMN reused_from_task_id;

ALTER TABLE lp_quote_bom_status
  DROP COLUMN manual_task_no,
  DROP COLUMN supplement_task_id;

ALTER TABLE lp_quote_bom_supplement_version DROP COLUMN task_id;
ALTER TABLE lp_quote_bom_supplement_detail DROP COLUMN task_id;
ALTER TABLE lp_quote_bom_package_reference DROP COLUMN task_id;
ALTER TABLE lp_quote_bom_package_reference_detail DROP COLUMN task_id;
ALTER TABLE lp_bom_costing_row_source_ref DROP COLUMN source_task_id;

DROP TABLE lp_bom_supplement_todo;
DROP TABLE lp_bom_supplement_task_quote_link;
DROP TABLE lp_bom_supplement_task;

DROP TABLE lp_integration_inbox;
DROP TABLE lp_integration_outbox;
DROP TABLE lp_quote_collaboration_external_task;
