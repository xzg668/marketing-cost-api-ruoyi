-- EasyData 清洗后的产品属性直接落在 U9 料品档案；旧库可能已由中台先行加列。
SET NAMES utf8mb4;

SET @v292_add_product_attr = IF(
  EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'lp_material_master_raw'
      AND COLUMN_NAME = 'product_attr'
  ),
  'SELECT 1',
  'ALTER TABLE lp_material_master_raw ADD COLUMN product_attr VARCHAR(128) NULL COMMENT ''产品属性'' AFTER imported_at'
);
PREPARE v292_stmt FROM @v292_add_product_attr;
EXECUTE v292_stmt;
DEALLOCATE PREPARE v292_stmt;
