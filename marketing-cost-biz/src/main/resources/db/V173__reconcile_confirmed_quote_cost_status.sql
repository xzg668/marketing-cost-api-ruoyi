-- 单产品核算工作台：已确认成本版本必须回写 OA 产品行核算状态。
-- 历史数据中存在 confirmed_cost_version_id/calc_at 已写入，但 calc_status 仍为异常默认值的情况。

SET NAMES utf8mb4;

ALTER TABLE oa_form_item
  MODIFY COLUMN calc_status VARCHAR(20) NOT NULL DEFAULT '未核算' COMMENT '产品行核算状态：未核算/已核算';

ALTER TABLE oa_form
  MODIFY COLUMN calc_status VARCHAR(20) NOT NULL DEFAULT '未核算';

UPDATE oa_form_item i
JOIN (
  SELECT v.*
  FROM lp_quote_cost_run_version v
  JOIN (
    SELECT oa_form_item_id, MAX(id) AS version_id
    FROM lp_quote_cost_run_version
    WHERE status = 'CONFIRMED'
      AND oa_form_item_id IS NOT NULL
    GROUP BY oa_form_item_id
  ) latest
    ON latest.version_id = v.id
) cv
  ON cv.oa_form_item_id = i.id
SET i.calc_status = '已核算',
    i.calc_at = COALESCE(i.calc_at, cv.confirmed_at, cv.trial_finished_at, NOW()),
    i.confirmed_cost_version_id = COALESCE(i.confirmed_cost_version_id, cv.id),
    i.updated_at = NOW()
WHERE COALESCE(i.deleted, 0) = 0
  AND (
    i.calc_status <> '已核算'
    OR i.calc_at IS NULL
    OR i.confirmed_cost_version_id IS NULL
  );

UPDATE oa_form_item
SET calc_status = '未核算',
    updated_at = NOW()
WHERE COALESCE(deleted, 0) = 0
  AND calc_status NOT IN ('未核算', '已核算', '试算中');

UPDATE oa_form
SET calc_status = '未核算',
    updated_at = NOW()
WHERE COALESCE(deleted, 0) = 0
  AND calc_status NOT IN ('未核算', '已核算', '试算中');

UPDATE oa_form f
JOIN (
  SELECT
    i.oa_form_id,
    COUNT(*) AS item_count,
    SUM(CASE WHEN i.calc_status = '已核算' THEN 1 ELSE 0 END) AS calculated_count,
    MAX(i.calc_at) AS max_calc_at
  FROM oa_form_item i
  WHERE COALESCE(i.deleted, 0) = 0
    AND i.material_no IS NOT NULL
    AND TRIM(i.material_no) <> ''
  GROUP BY i.oa_form_id
) agg
  ON agg.oa_form_id = f.id
SET f.calc_status = CASE
      WHEN agg.item_count > 0 AND agg.calculated_count >= agg.item_count THEN '已核算'
      ELSE '未核算'
    END,
    f.calc_at = CASE
      WHEN agg.item_count > 0 AND agg.calculated_count >= agg.item_count
        THEN COALESCE(f.calc_at, agg.max_calc_at)
      ELSE NULL
    END,
    f.updated_at = NOW()
WHERE COALESCE(f.deleted, 0) = 0;
