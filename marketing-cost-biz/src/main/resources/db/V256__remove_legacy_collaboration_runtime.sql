-- T14：旧协作运行链删除；共享BOM准备记录接管电子图库流程上下文，并清理旧菜单权限。
SET NAMES utf8mb4;

ALTER TABLE lp_quote_bom_preparation_record
  ADD COLUMN electronic_workflow_version INT NOT NULL DEFAULT 0 COMMENT '电子图库流程乐观锁版本',
  ADD COLUMN electronic_workflow_stage VARCHAR(32) NULL COMMENT '电子图库独立流程阶段',
  ADD COLUMN electronic_source_version_id BIGINT NULL COMMENT '当前电子图库补录版本ID',
  ADD COLUMN electronic_assignee_user_id BIGINT NULL COMMENT '当前电子图库处理人ID',
  ADD COLUMN electronic_assignee_name VARCHAR(128) NULL COMMENT '当前电子图库处理人',
  ADD COLUMN electronic_composition_fingerprint CHAR(64) NULL COMMENT '已发布混合BOM指纹',
  ADD INDEX idx_qbp_electronic_workflow (oa_form_item_id,cost_period_month,electronic_workflow_stage),
  ADD INDEX idx_qbp_electronic_source_version (electronic_source_version_id);

UPDATE lp_quote_bom_preparation_record p
JOIN lp_quote_bom_supplement_version v
  ON v.id=(
    SELECT selected.id
      FROM lp_quote_bom_supplement_version selected
     WHERE selected.preparation_id=p.id
       AND selected.bom_source='ELECTRONIC_DRAWING_EXCEL'
       AND selected.active_flag=1
     ORDER BY selected.version_no DESC,selected.id DESC
     LIMIT 1
  )
   SET p.electronic_source_version_id=v.id,
       p.electronic_composition_fingerprint=v.composition_fingerprint,
       p.electronic_workflow_stage=CASE
         WHEN v.version_status='APPROVED' AND v.composition_fingerprint IS NOT NULL THEN 'PUBLISHED'
         WHEN v.composition_fingerprint IS NOT NULL THEN 'COMPOSED'
         ELSE 'MAPPING_PENDING'
       END
 WHERE p.electronic_source_version_id IS NULL;

-- 通用核算队列继续保留“等待资料”这一业务状态，但不再沿用已删除的旧协作术语。
UPDATE lp_cost_run_task
   SET status='WAITING_INPUT'
 WHERE status='COLLABORATION';

UPDATE lp_cost_run_task_history
   SET status='WAITING_INPUT'
 WHERE status='COLLABORATION';

DELETE role_menu
  FROM sys_role_menu role_menu
  JOIN sys_menu menu ON menu.menu_id=role_menu.menu_id
 WHERE menu.perms LIKE 'collaboration:%'
    OR menu.component IN ('collaboration/technical/index','collaboration/finance/index');

DELETE FROM sys_menu
 WHERE perms LIKE 'collaboration:%'
    OR component IN ('collaboration/technical/index','collaboration/finance/index');
