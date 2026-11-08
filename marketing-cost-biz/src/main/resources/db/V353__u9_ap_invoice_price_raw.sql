-- U9 应付单发票价格接入表，对应中台 sy_quote_system.dwd_sy_u9c_yf_price_df。
-- 保留源表名和七个源字段，便于中台直接映射；id、synced_at 由 MySQL 自动生成。
-- approved_on、dt 保留源文本，不在接入阶段假定日期格式或执行日期转换。
-- 源数据没有单据号、行号，不能以料品/供应商/审核日期建立业务唯一键。
-- 本迁移仅创建原始接入表，不发布价格，也不改变核算取价优先级。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `dwd_sy_u9c_yf_price_df` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '本地自增主键，非源单据标识',
  `no_tax_price` DECIMAL(24,9) DEFAULT NULL COMMENT '不含税单价',
  `item_code` VARCHAR(128) DEFAULT NULL COMMENT '料品编码',
  `supply_code` VARCHAR(128) DEFAULT NULL COMMENT '供应商编码',
  `supply_name` TEXT COMMENT '供应商名称',
  `approved_on` VARCHAR(64) DEFAULT NULL COMMENT '审核日期原文',
  `unit_name` VARCHAR(64) DEFAULT NULL COMMENT '单位名称',
  `dt` VARCHAR(32) NOT NULL COMMENT '中台源分区值，原文保留',
  `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入MySQL时间',
  PRIMARY KEY (`id`),
  KEY `idx_yf_dt_item_supplier` (`dt`, `item_code`, `supply_code`),
  KEY `idx_yf_item_supplier_approved` (`item_code`, `supply_code`, `approved_on`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin ROW_FORMAT=DYNAMIC
  COMMENT='中台U9应付单发票价格原始快照';
