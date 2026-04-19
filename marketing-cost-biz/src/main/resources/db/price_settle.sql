-- 结算价单据主表
CREATE TABLE IF NOT EXISTS `lp_price_settle` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `buyer` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '购货方',
  `seller` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '销售方',
  `business_type` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '业务类型',
  `product_property` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '产品属性',
  `copper_price` decimal(12,2) DEFAULT NULL COMMENT '铜价',
  `month` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '月度',
  `approval_content` varchar(512) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '审批内容',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_settle_buyer` (`buyer`),
  KEY `idx_settle_month` (`month`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='结算价单据';

-- 结算价明细表
CREATE TABLE IF NOT EXISTS `lp_price_settle_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `settle_id` bigint NOT NULL COMMENT '关联主表lp_price_settle.id',
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL COMMENT '料号',
  `material_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '料品名称',
  `model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '型号',
  `planned_price` decimal(12,6) DEFAULT NULL COMMENT '计划价',
  `markup_ratio` decimal(10,6) DEFAULT NULL COMMENT '上浮比例',
  `base_settle_price` decimal(12,6) DEFAULT NULL COMMENT '基准结算价',
  `linked_settle_price` decimal(12,6) DEFAULT NULL COMMENT '联动结算价',
  `remark` varchar(512) COLLATE utf8mb3_bin DEFAULT NULL COMMENT '备注',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_settle_item_settle` (`settle_id`),
  KEY `idx_settle_item_material` (`material_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin COMMENT='结算价明细';
