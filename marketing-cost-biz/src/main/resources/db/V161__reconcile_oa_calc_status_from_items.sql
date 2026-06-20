-- 报价单表头核算状态以产品明细行为准：全部产品料号行已核算才算整单已核算。
UPDATE oa_form f
JOIN (
  SELECT
    item_status.oa_form_id,
    item_status.item_count,
    item_status.calculated_count,
    item_status.max_calc_at,
    CASE
      WHEN item_status.item_count > 0
       AND item_status.calculated_count >= item_status.item_count
        THEN '已核算'
      ELSE '未核算'
    END AS target_calc_status
  FROM (
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
  ) item_status
) agg ON agg.oa_form_id = f.id
SET f.calc_status = agg.target_calc_status,
    f.calc_at = CASE
      WHEN agg.target_calc_status = '已核算'
        THEN COALESCE(f.calc_at, agg.max_calc_at)
      ELSE NULL
    END,
    f.updated_at = NOW()
WHERE COALESCE(f.deleted, 0) = 0
  AND (
    COALESCE(f.calc_status, '') <> agg.target_calc_status
    OR (agg.target_calc_status = '未核算' AND f.calc_at IS NOT NULL)
  );
