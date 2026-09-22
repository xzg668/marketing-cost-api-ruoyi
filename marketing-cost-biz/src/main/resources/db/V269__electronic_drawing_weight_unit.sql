-- 新采集的图库重量保存源单位；既有源值和冻结版本不回填、不换算。
SET @tw269_weight_unit_sql = IF(
  EXISTS(SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'lp_electronic_drawing_source_node'
      AND column_name = 'reference_weight_unit'),
  'SELECT 1',
  'ALTER TABLE lp_electronic_drawing_source_node ADD COLUMN reference_weight_unit VARCHAR(8) DEFAULT NULL COMMENT ''源重量单位 g/kg；历史未标单位保持空'' AFTER reference_weight'
);
PREPARE tw269_weight_unit_statement FROM @tw269_weight_unit_sql;
EXECUTE tw269_weight_unit_statement;
DEALLOCATE PREPARE tw269_weight_unit_statement;
