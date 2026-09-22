-- 保留审批内容和所有价格记录；修正导入追加版本，归属记录使用真实因素批次主键。
SET @tw17_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_factor_upload_batch' AND column_name='technical_version_id'), 'SELECT 1', 'ALTER TABLE lp_factor_upload_batch ADD COLUMN technical_version_id BIGINT NULL');
PREPARE tw17_stmt FROM @tw17_ddl;
EXECUTE tw17_stmt;
DEALLOCATE PREPARE tw17_stmt;
SET @tw17_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_factor_upload_batch' AND column_name='technical_result_json'), 'SELECT 1', 'ALTER TABLE lp_factor_upload_batch ADD COLUMN technical_result_json MEDIUMTEXT NULL');
PREPARE tw17_stmt FROM @tw17_ddl;
EXECUTE tw17_stmt;
DEALLOCATE PREPARE tw17_stmt;
SET @tw17_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='lp_price_linked_item' AND column_name='technical_revision'), 'SELECT 1', 'ALTER TABLE lp_price_linked_item ADD COLUMN technical_revision INT NOT NULL DEFAULT 0');
PREPARE tw17_stmt FROM @tw17_ddl;
EXECUTE tw17_stmt;
DEALLOCATE PREPARE tw17_stmt;
SET @tw17_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_factor_upload_batch' AND index_name='idx_technical_version'), 'SELECT 1', 'CREATE INDEX idx_technical_version ON lp_factor_upload_batch(technical_version_id)');
PREPARE tw17_stmt FROM @tw17_ddl;
EXECUTE tw17_stmt;
DEALLOCATE PREPARE tw17_stmt;
-- 原两列唯一性扩为批准价格项内的修正版本；不删除、不改写已有价格数据。
SET @tw17_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='lp_price_linked_item' AND index_name='uk_tech_price_version_item' AND column_name='technical_revision'), 'SELECT 1', 'ALTER TABLE lp_price_linked_item DROP INDEX uk_tech_price_version_item, ADD UNIQUE INDEX uk_tech_price_version_item(technical_version_id,technical_item_key,technical_revision)');
PREPARE tw17_stmt FROM @tw17_ddl;
EXECUTE tw17_stmt;
DEALLOCATE PREPARE tw17_stmt;
