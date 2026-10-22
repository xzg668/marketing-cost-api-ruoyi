-- 原稿可以早于财务目录发布：完整工作簿只在现有来源批次保存一份。
SET @efp_workbook_ddl = (
  SELECT IF(COUNT(*) = 0,
    'ALTER TABLE lp_factor_upload_batch ADD COLUMN source_workbook LONGBLOB NULL COMMENT ''原稿工作簿原始字节，供发布时绑定财务因素''',
    'SELECT 1')
  FROM information_schema.columns
  WHERE table_schema = DATABASE() AND table_name = 'lp_factor_upload_batch' AND column_name = 'source_workbook'
);
PREPARE efp_workbook_statement FROM @efp_workbook_ddl;
EXECUTE efp_workbook_statement;
DEALLOCATE PREPARE efp_workbook_statement;
