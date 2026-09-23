-- SRM 每日固定采购价由 EasyData 直接落地到本表，应用校验完整批次后发布到正式价格表。
-- 该表此前只存在于开发数据库，必须纳入正式迁移，避免全新环境启动定时任务时报表不存在。

SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS `lp_price_fixed_item_srm_raw` (
  `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `company` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '公司名称',
  `material_code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '物料编码',
  `material_name` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '物料名称',
  `spec` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '规格',
  `sup_code` VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '供应商编码',
  `sup_name` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '供应商名称',
  `unit` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '单位',
  `price` DECIMAL(32,8) DEFAULT NULL COMMENT '未税固定价',
  `eff_date` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '生效日期文本',
  `exp_date` VARCHAR(32) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '失效日期文本',
  `source` VARCHAR(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '价格来源',
  `dt` VARCHAR(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT 'EasyData 数据日期，yyyy-MM-dd',
  `synced_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '写入 MySQL 的时间',
  PRIMARY KEY (`id`),
  KEY `idx_srm_raw_dt` (`dt`),
  KEY `idx_srm_raw_dt_company` (`dt`, `company`),
  KEY `idx_srm_raw_material` (`material_code`),
  KEY `idx_srm_raw_supplier` (`sup_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin
  COMMENT='SRM 固定价 EasyData 每日原始快照';
