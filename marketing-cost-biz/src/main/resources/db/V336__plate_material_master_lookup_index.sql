-- PMM-03：同组织有效型号的精确查询；函数表达式与 PlateMaterialMasterQuery 保持一致。
-- 不增加业务字段、不修改档案、不重建旧索引。
-- 原档案表接近 MySQL 行宽上限，完整 VARCHAR(255) 函数索引的隐藏列会超限。
-- 仅用 32 字节摘要筛选候选，查询仍保留完整型号二进制比较，摘要不是业务身份。
SET NAMES utf8mb4;
SET @pmm_index_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.statistics
    WHERE table_schema=DATABASE() AND table_name='lp_material_master_raw'
      AND index_name='idx_raw_plate_material_model'),
  'SELECT 1',
  'CREATE INDEX idx_raw_plate_material_model ON lp_material_master_raw
    (organization_code, active_flag, ((CAST(UNHEX(SHA2(TRIM(material_model),256)) AS BINARY(32)))))'
);
PREPARE pmm_index_stmt FROM @pmm_index_ddl;
EXECUTE pmm_index_stmt;
DEALLOCATE PREPARE pmm_index_stmt;
