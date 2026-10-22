-- 技术补录的新输入追加新结果；原稿计算仍沿用已经验收的写入键。
-- 保留既有结果及引用，仅放宽技术结果唯一键以包含完整输入指纹。
DROP PROCEDURE IF EXISTS v320_technical_inputs;
DELIMITER //
CREATE PROCEDURE v320_technical_inputs()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND column_name='technical_input_key') THEN
    ALTER TABLE lp_price_linked_calc_item ADD COLUMN technical_input_key VARCHAR(64)
      GENERATED ALWAYS AS (IF(source_kind='TECH_SUPPLEMENTAL',IFNULL(calc_fingerprint,''),'')) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_quote_scene_as_of_factor'
      AND column_name='technical_input_key') THEN
    ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_quote_scene_as_of_factor,
      ADD UNIQUE KEY uk_pl_calc_quote_scene_as_of_factor
        (business_unit_type,calc_scene,factor_source,oa_no,item_code,pricing_month,price_as_of_time,source_kind,price_org_code,technical_input_key);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_adjust_scene'
      AND column_name='technical_input_key') THEN
    ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_adjust_scene,
      ADD UNIQUE KEY uk_pl_calc_adjust_scene
        (business_unit_type,calc_scene,adjust_batch_id,item_code,pricing_month,source_kind,price_org_code,technical_input_key);
  END IF;
END//
DELIMITER ;
CALL v320_technical_inputs();
DROP PROCEDURE v320_technical_inputs;
