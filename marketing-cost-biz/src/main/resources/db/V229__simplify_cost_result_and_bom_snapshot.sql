-- =============================================================================
-- V229: 收敛成本结果主表并清理开发期遗留状态
--
-- 1. lp_quote_cost_run_version 作为唯一成本结果主表；
-- 2. 已有 lp_cost_run_result 数据迁入版本表后删除重复结果表；
-- 3. 删除没有运行入口的 OA 回写任务表；
-- 4. 月度 BOM 快照只保存来源，不再保存旧“人工冻结确认”状态。
-- =============================================================================

SET NAMES utf8mb4;

-- 历史无版本结果允许没有 OA 产品行 ID；新核算仍由业务代码强制要求产品行 ID。
ALTER TABLE `lp_quote_cost_run_version`
  MODIFY COLUMN `oa_form_item_id` BIGINT NULL COMMENT 'OA产品明细ID；仅迁移的早期历史结果可为空';

-- 现有版本优先保留版本表字段，只补齐早期重复结果表中独有的金额和完成时间。
UPDATE `lp_quote_cost_run_version` v
JOIN `lp_cost_run_result` r
  ON r.`cost_run_version_id` = v.`id`
SET v.`total_cost` = COALESCE(v.`total_cost`, r.`total_cost`),
    v.`finance_material_cost` = COALESCE(v.`finance_material_cost`, r.`finance_material_cost`),
    v.`oa_material_cost` = COALESCE(v.`oa_material_cost`, r.`oa_material_cost`),
    v.`cu_material_adjustment` = COALESCE(v.`cu_material_adjustment`, r.`cu_material_adjustment`),
    v.`final_quote_amount` = COALESCE(v.`final_quote_amount`, r.`final_quote_amount`),
    v.`trial_finished_at` = COALESCE(v.`trial_finished_at`, r.`calc_at`),
    v.`business_unit_type` = COALESCE(v.`business_unit_type`, r.`business_unit_type`);

-- 早期没有成本版本的结果转换为只读 HISTORY 版本，保留原金额和原完成时间。
INSERT INTO `lp_quote_cost_run_version` (
  `cost_run_no`, `version_no`, `oa_no`, `oa_form_item_id`, `product_code`,
  `pricing_month`, `result_period`, `price_prepare_no`, `input_fingerprint`,
  `algorithm_version`, `finance_material_cost`, `oa_material_cost`,
  `cu_material_adjustment`, `final_quote_amount`, `status`, `total_cost`,
  `part_item_count`, `cost_item_count`, `trial_started_at`, `trial_finished_at`,
  `business_unit_type`, `created_at`, `updated_at`
)
SELECT
  COALESCE(NULLIF(TRIM(r.`cost_run_no`), ''), CONCAT('LEGACY-RESULT-', r.`id`)),
  NULL,
  r.`oa_no`,
  COALESCE(
    CASE WHEN direct_form.`id` IS NOT NULL THEN direct_item.`id` END,
    active_item.`id`
  ),
  r.`product_code`,
  COALESCE(NULLIF(TRIM(r.`pricing_month`), ''), r.`period`),
  r.`period`,
  r.`price_prepare_no`,
  NULL,
  'LEGACY',
  r.`finance_material_cost`,
  r.`oa_material_cost`,
  r.`cu_material_adjustment`,
  r.`final_quote_amount`,
  CASE WHEN r.`total_cost` IS NULL THEN 'STALE' ELSE 'HISTORY' END,
  r.`total_cost`,
  (SELECT COUNT(*)
     FROM `lp_cost_run_part_item` p
    WHERE p.`cost_run_version_id` IS NULL
      AND p.`oa_no` = r.`oa_no`
      AND p.`product_code` = r.`product_code`),
  (SELECT COUNT(*)
     FROM `lp_cost_run_cost_item` c
    WHERE c.`cost_run_version_id` IS NULL
      AND c.`oa_no` = r.`oa_no`
      AND c.`product_code` = r.`product_code`),
  COALESCE(r.`calc_at`, r.`created_at`),
  COALESCE(r.`calc_at`, r.`updated_at`, r.`created_at`),
  r.`business_unit_type`,
  r.`created_at`,
  r.`updated_at`
FROM `lp_cost_run_result` r
LEFT JOIN `oa_form_item` direct_item
  ON direct_item.`id` = r.`oa_form_item_id`
 AND direct_item.`deleted` = 0
 AND BINARY direct_item.`material_no` = BINARY r.`product_code`
LEFT JOIN `oa_form` direct_form
  ON direct_form.`id` = direct_item.`oa_form_id`
 AND direct_form.`deleted` = 0
 AND BINARY direct_form.`oa_no` = BINARY r.`oa_no`
LEFT JOIN (
  SELECT f.`oa_no`, i.`material_no`, MIN(i.`id`) AS `id`
    FROM `oa_form` f
    JOIN `oa_form_item` i ON i.`oa_form_id` = f.`id` AND i.`deleted` = 0
   WHERE f.`deleted` = 0
   GROUP BY f.`oa_no`, i.`material_no`
  HAVING COUNT(*) = 1
) active_item
  ON BINARY active_item.`oa_no` = BINARY r.`oa_no`
 AND BINARY active_item.`material_no` = BINARY r.`product_code`
WHERE r.`cost_run_version_id` IS NULL;

-- 将早期无版本明细挂到刚迁入的 HISTORY 版本，历史明细仍可正常查看。
UPDATE `lp_cost_run_part_item` p
JOIN `lp_cost_run_result` r
  ON r.`cost_run_version_id` IS NULL
 AND r.`oa_no` = p.`oa_no`
 AND r.`product_code` = p.`product_code`
JOIN `lp_quote_cost_run_version` v
  ON BINARY v.`cost_run_no` = BINARY COALESCE(
       NULLIF(TRIM(r.`cost_run_no`), ''), CONCAT('LEGACY-RESULT-', r.`id`))
SET p.`cost_run_version_id` = v.`id`,
    p.`cost_run_no` = v.`cost_run_no`,
    p.`oa_form_item_id` = COALESCE(v.`oa_form_item_id`, p.`oa_form_item_id`)
WHERE p.`cost_run_version_id` IS NULL;

UPDATE `lp_cost_run_cost_item` c
JOIN `lp_cost_run_result` r
  ON r.`cost_run_version_id` IS NULL
 AND r.`oa_no` = c.`oa_no`
 AND r.`product_code` = c.`product_code`
JOIN `lp_quote_cost_run_version` v
  ON BINARY v.`cost_run_no` = BINARY COALESCE(
       NULLIF(TRIM(r.`cost_run_no`), ''), CONCAT('LEGACY-RESULT-', r.`id`))
SET c.`cost_run_version_id` = v.`id`,
    c.`cost_run_no` = v.`cost_run_no`,
    c.`oa_form_item_id` = COALESCE(v.`oa_form_item_id`, c.`oa_form_item_id`)
WHERE c.`cost_run_version_id` IS NULL;

-- 旧冻结快照中仍有效的 BOM 构建先迁到当前工作区，再删除冻结字段。
UPDATE `lp_quote_costing_workspace` w
JOIN `lp_quote_bom_monthly_snapshot` s
  ON s.`source_oa_form_item_id` = w.`oa_form_item_id`
 AND BINARY s.`cost_period_month` = BINARY w.`period_month`
SET w.`current_bom_build_batch_id` = s.`effective_build_batch_id`
WHERE s.`freeze_status` = 'FROZEN'
  AND s.`effective_build_batch_id` IS NOT NULL
  AND w.`current_bom_build_batch_id` IS NULL;

INSERT INTO `lp_quote_costing_workspace` (
  `oa_no`, `oa_form_item_id`, `product_code`, `period_month`, `business_unit_type`,
  `workspace_status`, `current_step`, `current_bom_build_batch_id`, `gap_count`,
  `carried_forward_price_count`, `lock_version`, `last_checked_at`, `created_at`, `updated_at`
)
SELECT
  s.`source_oa_no`,
  s.`source_oa_form_item_id`,
  s.`product_code`,
  s.`cost_period_month`,
  COALESCE(i.`business_unit_type`, f.`business_unit_type`),
  'BOM_READY',
  'PRICE_TYPE_CONFIRMATION',
  s.`effective_build_batch_id`,
  0,
  0,
  0,
  COALESCE(s.`frozen_at`, s.`updated_at`),
  s.`created_at`,
  s.`updated_at`
FROM `lp_quote_bom_monthly_snapshot` s
JOIN `oa_form_item` i ON i.`id` = s.`source_oa_form_item_id`
JOIN `oa_form` f ON f.`id` = i.`oa_form_id`
WHERE s.`freeze_status` = 'FROZEN'
  AND s.`effective_build_batch_id` IS NOT NULL
  AND NOT EXISTS (
    SELECT 1
      FROM `lp_quote_costing_workspace` w
     WHERE w.`oa_form_item_id` = s.`source_oa_form_item_id`
       AND BINARY w.`period_month` = BINARY s.`cost_period_month`
  );

UPDATE `lp_quote_bom_status` b
JOIN `lp_quote_bom_monthly_snapshot` s ON s.`id` = b.`sync_record_id`
SET b.`costing_build_batch_id` = COALESCE(
      b.`costing_build_batch_id`, s.`effective_build_batch_id`)
WHERE s.`freeze_status` = 'FROZEN'
  AND s.`effective_build_batch_id` IS NOT NULL;

DROP TABLE IF EXISTS `lp_quote_writeback_task`;
DROP TABLE IF EXISTS `lp_cost_run_result`;

-- 月度调价继续保留来源追溯，但来源对象已经统一为成本版本，字段名不能再指向已删除的结果表。
ALTER TABLE `lp_monthly_reprice_result`
  DROP INDEX `idx_reprice_result_source_result`,
  CHANGE COLUMN `source_cost_result_id` `source_cost_version_id` BIGINT DEFAULT NULL
    COMMENT '原OA成本版本ID lp_quote_cost_run_version.id',
  ADD KEY `idx_reprice_result_source_version` (`source_cost_version_id`);

ALTER TABLE `lp_quote_bom_monthly_snapshot`
  DROP INDEX `idx_quote_bom_monthly_effective_build`,
  DROP INDEX `idx_quote_bom_monthly_variant_hash`,
  DROP COLUMN `freeze_status`,
  DROP COLUMN `effective_build_batch_id`,
  DROP COLUMN `effective_variant_hash`,
  DROP COLUMN `frozen_at`,
  DROP COLUMN `frozen_by`;

-- 备选料按当前工作区保存，不再记录已删除的月度冻结卡片继承链。
ALTER TABLE `lp_quote_bom_alternative_selection`
  DROP INDEX `idx_quote_alt_inherited_snapshot`,
  DROP COLUMN `inherited_monthly_snapshot_id`;
