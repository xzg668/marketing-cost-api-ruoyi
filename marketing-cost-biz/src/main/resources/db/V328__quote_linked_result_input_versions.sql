-- Public quotation results with complete input fingerprints are immutable versions.
-- Technical results and monthly adjustment retain their existing uniqueness rules.
-- No source prices, stored results or quotation history are rewritten.
ALTER TABLE lp_price_linked_calc_item
  ADD COLUMN quote_input_key VARCHAR(128)
    GENERATED ALWAYS AS (
      IF(calc_scene='QUOTE' AND source_kind='PUBLIC', IFNULL(calc_fingerprint,''), '')
    ) STORED COMMENT '公共报价联动结果的完整输入版本',
  DROP INDEX uk_pl_calc_quote_scene_as_of_factor,
  ADD UNIQUE KEY uk_pl_calc_quote_scene_as_of_factor
    (business_unit_type,calc_scene,factor_source,oa_no,item_code,pricing_month,
     price_as_of_time,source_kind,price_org_code,technical_input_key,quote_input_key);
