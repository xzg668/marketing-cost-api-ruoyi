-- PRC-02：财务完整修订的调整原因；导入文件批次允许为空。
-- 复用上传批次，避免把人工调整原因放入错误消息或技术补录结果字段。
SET @v309_ddl = IF(
  EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
    AND table_name='lp_factor_upload_batch' AND column_name='publication_note'),
  'SELECT 1',
  'ALTER TABLE lp_factor_upload_batch ADD COLUMN publication_note VARCHAR(512) NULL'
);
PREPARE v309_stmt FROM @v309_ddl;
EXECUTE v309_stmt;
DEALLOCATE PREPARE v309_stmt;
