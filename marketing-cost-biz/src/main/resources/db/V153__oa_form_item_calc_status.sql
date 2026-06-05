ALTER TABLE oa_form_item
  ADD COLUMN calc_status VARCHAR(20) NOT NULL DEFAULT '未核算' COMMENT '产品行核算状态：未核算/已核算' AFTER valid_date,
  ADD COLUMN calc_at DATETIME DEFAULT NULL COMMENT '产品行核算完成时间' AFTER calc_status;

UPDATE oa_form_item i
JOIN oa_form f ON f.id = i.oa_form_id
JOIN lp_cost_run_result r
  ON r.oa_no = f.oa_no
 AND r.product_code = i.material_no
   SET i.calc_status = '已核算',
       i.calc_at = COALESCE(r.calc_at, f.calc_at, i.updated_at)
 WHERE r.total_cost IS NOT NULL
   AND COALESCE(i.deleted, 0) = 0
   AND i.material_no IS NOT NULL
   AND TRIM(i.material_no) <> '';

CREATE INDEX idx_oa_form_item_calc_status
  ON oa_form_item (oa_form_id, calc_status);
