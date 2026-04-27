-- V35：lp_price_linked_item 软删除支持
--
-- 背景：
--   /price/linked/result 的"删除"按钮原来走 BaseMapper.deleteById，物理 DELETE，
--   丢失审计轨迹；绑定回收站 / 恢复功能也无法支持。全局 @TableLogic 已在
--   application.yml 配置 (logic-delete-field=deleted / value=1)，只要给这张表
--   加 deleted 列 + entity 上的 @TableLogic 注解即可自动切软删语义。
--
-- 幂等：INFORMATION_SCHEMA 守护，已加过列 / 索引再跑不报错。
-- 回滚：纯 ADD COLUMN / ADD KEY，没有破坏性；revert Java 即可忽略列。

SET @has_deleted := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'lp_price_linked_item'
    AND COLUMN_NAME = 'deleted'
);
SET @sql := IF(@has_deleted = 0,
  'ALTER TABLE lp_price_linked_item
     ADD COLUMN deleted TINYINT(1) NOT NULL DEFAULT 0
       COMMENT ''软删除标记：0=正常 / 1=已删（与全局 @TableLogic 对齐）''
       AFTER effective_to',
  'SELECT ''V35: deleted column already exists, skip'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 查询侧过滤常走 deleted=0，单列索引即可；不做联合索引避免 (effective_to, deleted) 过度设计
SET @has_idx := (
  SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'lp_price_linked_item'
    AND INDEX_NAME = 'idx_lpli_deleted'
);
SET @sql := IF(@has_idx = 0,
  'ALTER TABLE lp_price_linked_item ADD KEY idx_lpli_deleted (deleted)',
  'SELECT ''V35: idx_lpli_deleted already exists, skip'' AS info');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
