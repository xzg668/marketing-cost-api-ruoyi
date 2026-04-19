/*
 Navicat Premium Data Transfer

 Source Server         : mysql
 Source Server Type    : MySQL
 Source Server Version : 90500
 Source Host           : localhost:3306
 Source Schema         : marketing_cost

 Target Server Type    : MySQL
 Target Server Version : 90500
 File Encoding         : 65001

 Date: 11/02/2026 09:47:32
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for lp_aux_rate_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_aux_rate_item`;
CREATE TABLE `lp_aux_rate_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `material_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `float_rate` decimal(10,4) NOT NULL,
  `period` char(7) COLLATE utf8mb3_bin DEFAULT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT 'import',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_aux_rate_material` (`material_code`),
  KEY `idx_aux_rate_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=9 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_aux_rate_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_aux_rate_item` (`id`, `material_code`, `material_name`, `spec`, `model`, `float_rate`, `period`, `source`, `created_at`, `updated_at`) VALUES (5, '1001', '气体', '', '', 0.0100, '2026-02', 'import', '2026-02-10 15:22:03', '2026-02-10 15:22:03');
INSERT INTO `lp_aux_rate_item` (`id`, `material_code`, `material_name`, `spec`, `model`, `float_rate`, `period`, `source`, `created_at`, `updated_at`) VALUES (6, '1002', '表面处理', '', '', 0.0200, '2026-02', 'import', '2026-02-10 15:22:03', '2026-02-10 15:22:03');
INSERT INTO `lp_aux_rate_item` (`id`, `material_code`, `material_name`, `spec`, `model`, `float_rate`, `period`, `source`, `created_at`, `updated_at`) VALUES (7, '1003', '炉焊', '', '', 0.0300, '2026-02', 'import', '2026-02-10 15:22:03', '2026-02-10 15:22:03');
INSERT INTO `lp_aux_rate_item` (`id`, `material_code`, `material_name`, `spec`, `model`, `float_rate`, `period`, `source`, `created_at`, `updated_at`) VALUES (8, '1004', '清洗费', '', '', 0.0400, '2026-02', 'import', '2026-02-10 15:22:03', '2026-02-10 15:22:03');
COMMIT;

-- ----------------------------
-- Table structure for lp_aux_subject
-- ----------------------------
DROP TABLE IF EXISTS `lp_aux_subject`;
CREATE TABLE `lp_aux_subject` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `product_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `ref_material_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `aux_subject_code` varchar(32) COLLATE utf8mb3_bin NOT NULL,
  `aux_subject_name` varchar(128) COLLATE utf8mb3_bin NOT NULL,
  `unit_price` decimal(18,2) DEFAULT NULL,
  `period` char(7) COLLATE utf8mb3_bin NOT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT 'import',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_aux_material` (`material_code`),
  KEY `idx_aux_subject` (`aux_subject_code`),
  KEY `idx_aux_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_aux_subject
-- ----------------------------
BEGIN;
INSERT INTO `lp_aux_subject` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `aux_subject_code`, `aux_subject_name`, `unit_price`, `period`, `source`, `created_at`, `updated_at`) VALUES (1, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', NULL, '1001', '气体', 1.00, '2026-02', 'import', '2026-02-10 15:13:00', '2026-02-10 15:13:00');
INSERT INTO `lp_aux_subject` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `aux_subject_code`, `aux_subject_name`, `unit_price`, `period`, `source`, `created_at`, `updated_at`) VALUES (2, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', NULL, '1002', '表面处理', 2.00, '2026-02', 'import', '2026-02-10 15:13:00', '2026-02-10 15:13:00');
INSERT INTO `lp_aux_subject` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `aux_subject_code`, `aux_subject_name`, `unit_price`, `period`, `source`, `created_at`, `updated_at`) VALUES (3, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', NULL, '1003', '炉焊', 3.00, '2026-02', 'import', '2026-02-10 15:13:00', '2026-02-10 15:13:00');
INSERT INTO `lp_aux_subject` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `aux_subject_code`, `aux_subject_name`, `unit_price`, `period`, `source`, `created_at`, `updated_at`) VALUES (4, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', NULL, '1004', '清洗费', 4.00, '2026-02', 'import', '2026-02-10 15:13:00', '2026-02-10 15:13:00');
COMMIT;

-- ----------------------------
-- Table structure for lp_bom_manage_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_bom_manage_item`;
CREATE TABLE `lp_bom_manage_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `oa_form_id` bigint DEFAULT NULL,
  `oa_form_item_id` bigint DEFAULT NULL,
  `material_no` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `product_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `product_spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `product_model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `customer_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `copper_price_tax` decimal(18,2) DEFAULT NULL,
  `zinc_price_tax` decimal(18,2) DEFAULT NULL,
  `aluminum_price_tax` decimal(18,2) DEFAULT NULL,
  `steel_price_tax` decimal(18,2) DEFAULT NULL,
  `bom_code` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `root_item_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `shape_attr` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `bom_qty` bigint DEFAULT NULL,
  `material` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `filter_rule` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_bom_manage_oa` (`oa_no`),
  KEY `idx_bom_manage_material` (`material_no`),
  KEY `idx_bom_manage_bom` (`bom_code`),
  KEY `idx_bom_manage_item` (`item_code`)
) ENGINE=InnoDB AUTO_INCREMENT=26 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_bom_manage_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_bom_manage_item` (`id`, `oa_no`, `oa_form_id`, `oa_form_item_id`, `material_no`, `product_name`, `product_spec`, `product_model`, `customer_name`, `copper_price_tax`, `zinc_price_tax`, `aluminum_price_tax`, `steel_price_tax`, `bom_code`, `root_item_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `shape_attr`, `bom_qty`, `material`, `source`, `filter_rule`, `created_at`, `updated_at`) VALUES (21, 'FI-SR-006-20260116-0527', 1, 1, '1008900031271', '热力膨胀阀', '规格A', '型号A', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, 'BOM0001', '1008900031271', '250012748', '瓦楞纸箱', 'BZ-RFG-F05001-02_外*425*275*275', '', '采购件', 1, '', 'import', 'A', '2026-02-10 14:21:32', '2026-02-10 14:21:32');
INSERT INTO `lp_bom_manage_item` (`id`, `oa_no`, `oa_form_id`, `oa_form_item_id`, `material_no`, `product_name`, `product_spec`, `product_model`, `customer_name`, `copper_price_tax`, `zinc_price_tax`, `aluminum_price_tax`, `steel_price_tax`, `bom_code`, `root_item_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `shape_attr`, `bom_qty`, `material`, `source`, `filter_rule`, `created_at`, `updated_at`) VALUES (22, 'FI-SR-006-20260116-0527', 1, 1, '1008900031271', '热力膨胀阀', '规格A', '型号A', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, 'BOM0001', '1008900031271', '9990000056820', '端子', 'MOLEX 0850-0031', 'MOLEX 0850-0031', '采购件', 2, 'C3771', 'import', 'A', '2026-02-10 14:21:32', '2026-02-10 14:21:32');
INSERT INTO `lp_bom_manage_item` (`id`, `oa_no`, `oa_form_id`, `oa_form_item_id`, `material_no`, `product_name`, `product_spec`, `product_model`, `customer_name`, `copper_price_tax`, `zinc_price_tax`, `aluminum_price_tax`, `steel_price_tax`, `bom_code`, `root_item_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `shape_attr`, `bom_qty`, `material`, `source`, `filter_rule`, `created_at`, `updated_at`) VALUES (23, 'FI-SR-006-20260116-0527', 1, 1, '1008900031271', '热力膨胀阀', '规格A', '型号A', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, 'BOM0001', '1008900031271', '1008000300939', '阀芯部件', 'RFGC-23158', 'RFGC-23158', '采购件', 1, 'SUS305', 'import', 'A', '2026-02-10 14:21:32', '2026-02-10 14:21:32');
INSERT INTO `lp_bom_manage_item` (`id`, `oa_no`, `oa_form_id`, `oa_form_item_id`, `material_no`, `product_name`, `product_spec`, `product_model`, `customer_name`, `copper_price_tax`, `zinc_price_tax`, `aluminum_price_tax`, `steel_price_tax`, `bom_code`, `root_item_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `shape_attr`, `bom_qty`, `material`, `source`, `filter_rule`, `created_at`, `updated_at`) VALUES (24, 'FI-SR-006-20260116-0527', 1, 1, '1008900031271', '热力膨胀阀', '规格A', '型号A', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, 'BOM0001', '1008900031271', '1008000300950', '调节部件', 'RFGN-14003', 'RFGN-14003', '采购件', 1, 'SUS304', 'import', 'A', '2026-02-10 14:21:32', '2026-02-10 14:21:32');
INSERT INTO `lp_bom_manage_item` (`id`, `oa_no`, `oa_form_id`, `oa_form_item_id`, `material_no`, `product_name`, `product_spec`, `product_model`, `customer_name`, `copper_price_tax`, `zinc_price_tax`, `aluminum_price_tax`, `steel_price_tax`, `bom_code`, `root_item_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `shape_attr`, `bom_qty`, `material`, `source`, `filter_rule`, `created_at`, `updated_at`) VALUES (25, 'FI-SR-006-20260116-0527', 1, 1, '1008900031271', '热力膨胀阀', '规格A', '型号A', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, 'BOM0001', '1008900031271', '1008000300944', '阀体部件', 'RFG-K04-002784', 'RFG-K04-002784', '制造件', 1, 'SUS303', 'import', 'A', '2026-02-10 14:21:32', '2026-02-10 14:21:32');
COMMIT;

-- ----------------------------
-- Table structure for lp_bom_manual_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_bom_manual_item`;
CREATE TABLE `lp_bom_manual_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `bom_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `item_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `item_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `bom_level` int NOT NULL,
  `parent_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `shape_attr` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `bom_qty` decimal(18,6) DEFAULT NULL,
  `material` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT 'import',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_bom_code` (`bom_code`),
  KEY `idx_bom_item_code` (`item_code`),
  KEY `idx_bom_parent_code` (`parent_code`),
  KEY `idx_bom_level` (`bom_level`)
) ENGINE=InnoDB AUTO_INCREMENT=29 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_bom_manual_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (19, 'BOM0001', '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 1, NULL, '制造件', 1.000000, '', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (20, 'BOM0001', '102053856', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 2, '1008900031271', '制造件', 1.000000, 'SUS301', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (21, 'BOM0001', '1008000300173', '阀体', 'RFG-K04-003424', 'RFG-K04-003424', 3, '102053856', '制造件', 1.000000, 'SUS302', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (22, 'BOM0001', '1008000300944', '阀体部件', 'RFG-K04-002784', 'RFG-K04-002784', 4, '1008000300173', '制造件', 1.000000, 'SUS303', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (23, 'BOM0001', '1008000300950', '调节部件', 'RFGN-14003', 'RFGN-14003', 4, '1008000300173', '采购件', 1.000000, 'SUS304', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (24, 'BOM0001', '1008000300939', '阀芯部件', 'RFGC-23158', 'RFGC-23158', 4, '1008000300173', '采购件', 1.000000, 'SUS305', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (25, 'BOM0001', '9990000056820', '端子', 'MOLEX 0850-0031', 'MOLEX 0850-0031', 3, '102053856', '采购件', 2.000000, 'C3771', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (26, 'BOM0001', '9830000025884', '包装组件', 'BZ-RFGF001_标准大包装', '', 2, '1008900031271', '制造件', 1.000000, '', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (27, 'BOM0001', '250012748', '瓦楞纸箱', 'BZ-RFG-F05001-02_外*425*275*275', '', 3, '9830000025884', '采购件', 1.000000, '', 'import', '2026-02-05 11:24:46', '2026-02-05 11:24:46');
INSERT INTO `lp_bom_manual_item` (`id`, `bom_code`, `item_code`, `item_name`, `item_spec`, `item_model`, `bom_level`, `parent_code`, `shape_attr`, `bom_qty`, `material`, `source`, `created_at`, `updated_at`) VALUES (28, 'BOM0001', '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 1, NULL, '制造件', 1.000000, '', 'import', '2026-02-10 14:21:11', '2026-02-10 14:21:11');
COMMIT;

-- ----------------------------
-- Table structure for lp_cost_run_cost_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_cost_run_cost_item`;
CREATE TABLE `lp_cost_run_cost_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) NOT NULL,
  `product_code` varchar(64) NOT NULL,
  `line_no` int DEFAULT NULL,
  `cost_code` varchar(40) NOT NULL,
  `cost_name` varchar(120) NOT NULL,
  `base_amount` decimal(18,6) DEFAULT NULL,
  `rate` decimal(10,6) DEFAULT NULL,
  `amount` decimal(18,6) DEFAULT NULL,
  `source_table` varchar(64) DEFAULT NULL,
  `source_id` bigint DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cost_run_cost` (`oa_no`,`product_code`),
  KEY `idx_cost_run_cost_code` (`cost_code`)
) ENGINE=InnoDB AUTO_INCREMENT=961 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_cost_run_cost_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (941, 'FI-SR-006-20260116-0527', '1008900031271', 1, 'MATERIAL', '材料费', NULL, NULL, 55.210000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (942, 'FI-SR-006-20260116-0527', '1008900031271', 2, 'AUX_1001', '气体', 1.000000, 0.010000, 0.010000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (943, 'FI-SR-006-20260116-0527', '1008900031271', 3, 'AUX_1002', '表面处理', 2.000000, 0.020000, 0.040000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (944, 'FI-SR-006-20260116-0527', '1008900031271', 4, 'AUX_1003', '炉焊', 3.000000, 0.030000, 0.090000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (945, 'FI-SR-006-20260116-0527', '1008900031271', 5, 'AUX_1004', '清洗费', 4.000000, 0.040000, 0.160000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (946, 'FI-SR-006-20260116-0527', '1008900031271', 6, 'DIRECT_LABOR', '直接人工工资', NULL, NULL, 1.000000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (947, 'FI-SR-006-20260116-0527', '1008900031271', 7, 'INDIRECT_LABOR', '辅助人工工资', NULL, NULL, 2.000000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (948, 'FI-SR-006-20260116-0527', '1008900031271', 8, 'LOSS', '净损失率', 58.210000, 0.010000, 0.582100, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (949, 'FI-SR-006-20260116-0527', '1008900031271', 9, 'MANUFACTURE', '制造费用', 65.324556, 0.100000, 6.532456, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (950, 'FI-SR-006-20260116-0527', '1008900031271', 10, 'MANUFACTURE_COST', '制造成本', NULL, NULL, 65.324556, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (951, 'FI-SR-006-20260116-0527', '1008900031271', 11, 'MGMT_EXP', '管理费用', 65.324556, 0.080000, 5.225964, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (952, 'FI-SR-006-20260116-0527', '1008900031271', 12, 'SALES_EXP', '销售费用', 65.324556, 0.050000, 3.266228, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (953, 'FI-SR-006-20260116-0527', '1008900031271', 13, 'FIN_EXP', '财务费用', 65.324556, 0.020000, 1.306491, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (954, 'FI-SR-006-20260116-0527', '1008900031271', 14, 'OTHER_EXP_1', '认证费', NULL, NULL, 10.000000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (955, 'FI-SR-006-20260116-0527', '1008900031271', 15, 'OTHER_EXP_2', '模具费', NULL, NULL, 5.000000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (956, 'FI-SR-006-20260116-0527', '1008900031271', 16, 'TOTAL', '不含税总成本', NULL, NULL, 90.123239, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (957, 'FI-SR-006-20260116-0527', '1008900031271', 17, 'OVERHAUL', '大修费', 50.000000, 0.000500, 0.025000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (958, 'FI-SR-006-20260116-0527', '1008900031271', 18, 'TOOLING_REPAIR', '工装零星修理费', 50.000000, 0.001000, 0.050000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (959, 'FI-SR-006-20260116-0527', '1008900031271', 19, 'WATER_POWER', '水电费', 50.000000, 0.001500, 0.075000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_cost_item` (`id`, `oa_no`, `product_code`, `line_no`, `cost_code`, `cost_name`, `base_amount`, `rate`, `amount`, `source_table`, `source_id`, `created_at`, `updated_at`) VALUES (960, 'FI-SR-006-20260116-0527', '1008900031271', 20, 'DEPT_OTHER', '其他费用', 50.000000, 0.002000, 0.100000, NULL, NULL, '2026-02-11 09:38:33', '2026-02-11 09:38:33');
COMMIT;

-- ----------------------------
-- Table structure for lp_cost_run_part_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_cost_run_part_item`;
CREATE TABLE `lp_cost_run_part_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) NOT NULL,
  `product_code` varchar(64) NOT NULL,
  `line_no` int DEFAULT NULL,
  `part_code` varchar(64) DEFAULT NULL,
  `part_name` varchar(120) DEFAULT NULL,
  `part_drawing_no` varchar(64) DEFAULT NULL,
  `material` varchar(64) DEFAULT NULL,
  `shape_attr` varchar(64) DEFAULT NULL,
  `price_source` varchar(40) DEFAULT NULL,
  `unit_price` decimal(18,6) DEFAULT NULL,
  `qty` decimal(18,6) DEFAULT NULL,
  `amount` decimal(18,6) DEFAULT NULL,
  `remark` varchar(200) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_cost_run_part` (`oa_no`,`product_code`),
  KEY `idx_cost_run_part_code` (`part_code`)
) ENGINE=InnoDB AUTO_INCREMENT=366 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_cost_run_part_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_cost_run_part_item` (`id`, `oa_no`, `product_code`, `line_no`, `part_code`, `part_name`, `part_drawing_no`, `material`, `shape_attr`, `price_source`, `unit_price`, `qty`, `amount`, `remark`, `created_at`, `updated_at`) VALUES (361, 'FI-SR-006-20260116-0527', '1008900031271', NULL, '250012748', '瓦楞纸箱', '', '', '采购件', '', 1.000000, 1.000000, 1.000000, '', '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_part_item` (`id`, `oa_no`, `product_code`, `line_no`, `part_code`, `part_name`, `part_drawing_no`, `material`, `shape_attr`, `price_source`, `unit_price`, `qty`, `amount`, `remark`, `created_at`, `updated_at`) VALUES (362, 'FI-SR-006-20260116-0527', '1008900031271', NULL, '9990000056820', '端子', 'MOLEX 0850-0031', 'C3771', '采购件', '', 3.000000, 2.000000, 6.000000, '', '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_part_item` (`id`, `oa_no`, `product_code`, `line_no`, `part_code`, `part_name`, `part_drawing_no`, `material`, `shape_attr`, `price_source`, `unit_price`, `qty`, `amount`, `remark`, `created_at`, `updated_at`) VALUES (363, 'FI-SR-006-20260116-0527', '1008900031271', NULL, '1008000300939', '阀芯部件', 'RFGC-23158', 'SUS305', '采购件', '', 10.670000, 1.000000, 10.670000, '', '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_part_item` (`id`, `oa_no`, `product_code`, `line_no`, `part_code`, `part_name`, `part_drawing_no`, `material`, `shape_attr`, `price_source`, `unit_price`, `qty`, `amount`, `remark`, `created_at`, `updated_at`) VALUES (364, 'FI-SR-006-20260116-0527', '1008900031271', NULL, '1008000300950', '调节部件', 'RFGN-14003', 'SUS304', '采购件', '', 0.130000, 1.000000, 0.130000, '', '2026-02-11 09:38:33', '2026-02-11 09:38:33');
INSERT INTO `lp_cost_run_part_item` (`id`, `oa_no`, `product_code`, `line_no`, `part_code`, `part_name`, `part_drawing_no`, `material`, `shape_attr`, `price_source`, `unit_price`, `qty`, `amount`, `remark`, `created_at`, `updated_at`) VALUES (365, 'FI-SR-006-20260116-0527', '1008900031271', NULL, '1008000300944', '阀体部件', 'RFG-K04-002784', 'SUS303', '制造件', '', 36.860000, 1.000000, 36.860000, '', '2026-02-11 09:38:33', '2026-02-11 09:38:33');
COMMIT;

-- ----------------------------
-- Table structure for lp_cost_run_result
-- ----------------------------
DROP TABLE IF EXISTS `lp_cost_run_result`;
CREATE TABLE `lp_cost_run_result` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) NOT NULL,
  `product_code` varchar(64) NOT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `product_model` varchar(120) DEFAULT NULL,
  `customer_name` varchar(120) DEFAULT NULL,
  `business_unit` varchar(120) DEFAULT NULL,
  `department` varchar(120) DEFAULT NULL,
  `period` varchar(7) NOT NULL,
  `currency` varchar(10) DEFAULT NULL,
  `unit` varchar(20) DEFAULT NULL,
  `total_cost` decimal(18,6) DEFAULT NULL,
  `calc_status` varchar(20) NOT NULL,
  `calc_at` datetime DEFAULT NULL,
  `product_attr` varchar(120) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_cost_run_result` (`oa_no`,`product_code`,`period`),
  KEY `idx_cost_run_oa` (`oa_no`),
  KEY `idx_cost_run_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=2 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_cost_run_result
-- ----------------------------
BEGIN;
INSERT INTO `lp_cost_run_result` (`id`, `oa_no`, `product_code`, `product_name`, `product_model`, `customer_name`, `business_unit`, `department`, `period`, `currency`, `unit`, `total_cost`, `calc_status`, `calc_at`, `product_attr`, `created_at`, `updated_at`) VALUES (1, 'FI-SR-006-20260116-0527', '1008900031271', '热力膨胀阀', '型号A', 'YEONGJIN KOREA CO.,LTD.', NULL, NULL, '2026-01', NULL, NULL, 90.123239, '未核算', '2026-02-11 09:38:33', '标准品', '2026-02-10 11:09:57', '2026-02-10 11:09:57');
COMMIT;

-- ----------------------------
-- Table structure for lp_department_fund_rate
-- ----------------------------
DROP TABLE IF EXISTS `lp_department_fund_rate`;
CREATE TABLE `lp_department_fund_rate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `business_unit` varchar(120) NOT NULL,
  `overhaul_rate` decimal(10,2) NOT NULL,
  `tooling_repair_rate` decimal(10,2) NOT NULL,
  `water_power_rate` decimal(10,2) NOT NULL,
  `other_rate` decimal(10,2) NOT NULL,
  `uplift_rate` decimal(10,2) NOT NULL,
  `manhour_rate` decimal(10,2) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_fund_unique` (`business_unit`),
  KEY `idx_dept_fund_bu` (`business_unit`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_department_fund_rate
-- ----------------------------
BEGIN;
INSERT INTO `lp_department_fund_rate` (`id`, `business_unit`, `overhaul_rate`, `tooling_repair_rate`, `water_power_rate`, `other_rate`, `uplift_rate`, `manhour_rate`, `created_at`, `updated_at`) VALUES (1, '四通阀事业部', 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, '2026-02-06 10:26:44', '2026-02-06 10:26:44');
INSERT INTO `lp_department_fund_rate` (`id`, `business_unit`, `overhaul_rate`, `tooling_repair_rate`, `water_power_rate`, `other_rate`, `uplift_rate`, `manhour_rate`, `created_at`, `updated_at`) VALUES (2, '电子产品事业部', 0.01, 0.02, 0.03, 0.04, 0.05, 0.06, '2026-02-06 10:26:44', '2026-02-06 10:26:44');
COMMIT;

-- ----------------------------
-- Table structure for lp_finance_base_price
-- ----------------------------
DROP TABLE IF EXISTS `lp_finance_base_price`;
CREATE TABLE `lp_finance_base_price` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `price_month` char(7) COLLATE utf8mb3_bin NOT NULL,
  `seq` int DEFAULT NULL,
  `factor_name` varchar(255) COLLATE utf8mb3_bin NOT NULL,
  `short_name` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `factor_code` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `price_source` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `price` decimal(18,6) NOT NULL,
  `unit` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL,
  `link_type` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_fin_base_month` (`price_month`),
  KEY `idx_fin_base_factor` (`factor_code`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_finance_base_price
-- ----------------------------
BEGIN;
INSERT INTO `lp_finance_base_price` (`id`, `price_month`, `seq`, `factor_name`, `short_name`, `factor_code`, `price_source`, `price`, `unit`, `link_type`, `created_at`, `updated_at`) VALUES (7, '2026-02', 5, '上月16日至本月15日中华商务网长江现货市场1#电解铜含税平均价格', '1#Cu', 'Cu', '平均价', 86.638000, '公斤', '固定', '2026-02-10 16:12:14', '2026-02-10 16:12:14');
INSERT INTO `lp_finance_base_price` (`id`, `price_month`, `seq`, `factor_name`, `short_name`, `factor_code`, `price_source`, `price`, `unit`, `link_type`, `created_at`, `updated_at`) VALUES (8, '2026-02', 6, '上月16日至本月15日中华商务网长江现货市场1#电解锌含税平均价格', '1#Zn', 'Zn', '平均价', 22.215000, '公斤', '固定', '2026-02-10 16:12:14', '2026-02-10 16:12:14');
INSERT INTO `lp_finance_base_price` (`id`, `price_month`, `seq`, `factor_name`, `short_name`, `factor_code`, `price_source`, `price`, `unit`, `link_type`, `created_at`, `updated_at`) VALUES (9, '2026-02', 7, '入库月上月16日至本月15日上海有色网（SMM）现货市场的1#电解铜含税平均价格', '1#Cu', 'Cu', '平均价', 86.494000, '公斤', '固定', '2026-02-10 16:12:14', '2026-02-10 16:12:14');
INSERT INTO `lp_finance_base_price` (`id`, `price_month`, `seq`, `factor_name`, `short_name`, `factor_code`, `price_source`, `price`, `unit`, `link_type`, `created_at`, `updated_at`) VALUES (10, '2026-02', 8, '入库月上月16日至本月15日上海有色网（SMM）现货市场的1#电解锌含税平均价格', '1#Zn', 'Zn', '平均价', 22.230000, '公斤', '固定', '2026-02-10 16:12:14', '2026-02-10 16:12:14');
INSERT INTO `lp_finance_base_price` (`id`, `price_month`, `seq`, `factor_name`, `short_name`, `factor_code`, `price_source`, `price`, `unit`, `link_type`, `created_at`, `updated_at`) VALUES (11, '2026-02', 18, '上月16日至本月15日灵通信息网佛山地区“美国柜装黄铜Fe＜2%”不含税平均价格', '美国柜装黄铜', NULL, '平均价', 53.564000, '公斤', '联动', '2026-02-10 16:12:14', '2026-02-10 16:12:14');
COMMIT;

-- ----------------------------
-- Table structure for lp_manufacture_rate
-- ----------------------------
DROP TABLE IF EXISTS `lp_manufacture_rate`;
CREATE TABLE `lp_manufacture_rate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `company` varchar(80) NOT NULL,
  `business_unit` varchar(80) NOT NULL,
  `product_category` varchar(80) NOT NULL,
  `product_subcategory` varchar(80) NOT NULL,
  `product_spec` varchar(120) DEFAULT NULL,
  `product_model` varchar(120) DEFAULT NULL,
  `fee_rate` decimal(10,6) NOT NULL,
  `period` varchar(20) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_mfr_unique` (`company`,`business_unit`,`product_category`,`product_subcategory`,`period`),
  KEY `idx_mfr_bu` (`business_unit`),
  KEY `idx_mfr_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_manufacture_rate
-- ----------------------------
BEGIN;
INSERT INTO `lp_manufacture_rate` (`id`, `company`, `business_unit`, `product_category`, `product_subcategory`, `product_spec`, `product_model`, `fee_rate`, `period`, `created_at`, `updated_at`) VALUES (1, '浙江三花商用制冷有限公司', '商用部品事业部', '电磁阀', 'FDF11A/13A', '该列暂不需要', '该列暂不需要', 0.100000, '2026-02', '2026-02-10 15:38:24', '2026-02-10 15:38:24');
INSERT INTO `lp_manufacture_rate` (`id`, `company`, `business_unit`, `product_category`, `product_subcategory`, `product_spec`, `product_model`, `fee_rate`, `period`, `created_at`, `updated_at`) VALUES (2, '浙江三花商用制冷有限公司', '四通阀事业部', '热力膨胀阀', 'RFGF/RFGK', NULL, NULL, 0.100000, '2026-02', '2026-02-10 15:38:24', '2026-02-10 15:38:24');
INSERT INTO `lp_manufacture_rate` (`id`, `company`, `business_unit`, `product_category`, `product_subcategory`, `product_spec`, `product_model`, `fee_rate`, `period`, `created_at`, `updated_at`) VALUES (3, '浙江三花板换科技有限公司', '板换事业部', '板式换热器', '钎焊板式换热器', NULL, NULL, 0.120000, '2026-02', '2026-02-10 15:38:24', '2026-02-10 15:38:24');
COMMIT;

-- ----------------------------
-- Table structure for lp_material_master
-- ----------------------------
DROP TABLE IF EXISTS `lp_material_master`;
CREATE TABLE `lp_material_master` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `material_name` varchar(128) COLLATE utf8mb3_bin NOT NULL,
  `item_spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `drawing_no` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `shape_attr` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `material` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `theoretical_weight_g` decimal(18,6) DEFAULT NULL,
  `net_weight_kg` decimal(18,6) DEFAULT NULL,
  `biz_unit` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `production_dept` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `production_workshop` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT 'import',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_material_code` (`material_code`),
  KEY `idx_material_name` (`material_name`),
  KEY `idx_item_spec` (`item_spec`),
  KEY `idx_item_model` (`item_model`),
  KEY `idx_drawing_no` (`drawing_no`)
) ENGINE=InnoDB AUTO_INCREMENT=10 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_material_master
-- ----------------------------
BEGIN;
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (1, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 'RFG-K19060A-3128', '制造件', '', NULL, 0.353500, '', '商用四通阀-装配一车间', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (2, '102053856', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 'RFG-K19060A-3128', '制造件', 'SUS301', 4.000000, NULL, '', '商用四通阀-装配一车间', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (3, '1008000300173', '阀体', 'RFG-K04-003424', 'RFG-K04-003424', 'RFG-K04-003424', '制造件', 'SUS302', 5.000000, NULL, '', '商用四通阀-装配一车间', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (4, '1008000300944', '阀体部件', 'RFG-K04-002784', 'RFG-K04-002784', 'RFG-K04-002784', '制造件', 'SUS303', 6.000000, NULL, '', '商用四通阀-装配一车间', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (5, '1008000300950', '调节部件', 'RFGN-14003', 'RFGN-14003', 'RFGN-14003', '采购件', 'SUS304', 7.000000, NULL, '', '', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (6, '1008000300939', '阀芯部件', 'RFGC-23158', 'RFGC-23158', 'RFGC-23158', '采购件', 'SUS305', 8.000000, NULL, '', '', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (7, '9990000056820', '端子', 'MOLEX 0850-0031', 'MOLEX 0850-0031', 'MOLEX 0850-0031', '采购件', 'C3771', 9.000000, NULL, '', '', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (8, '9830000025884', '包装组件', 'BZ-RFGF001_标准大包装', '', '', '制造件', '', NULL, NULL, '', '', '', 'import', '2026-02-05 16:04:58', '2026-02-05 16:04:58');
INSERT INTO `lp_material_master` (`id`, `material_code`, `material_name`, `item_spec`, `item_model`, `drawing_no`, `shape_attr`, `material`, `theoretical_weight_g`, `net_weight_kg`, `biz_unit`, `production_dept`, `production_workshop`, `source`, `created_at`, `updated_at`) VALUES (9, '250012748', '瓦楞纸箱', 'BZ-RFG-F05001-02_外*425*275*275', '', '', '采购件', '', NULL, NULL, '', '', '', 'import', '2026-02-05 16:04:59', '2026-02-05 16:04:59');
COMMIT;

-- ----------------------------
-- Table structure for lp_material_price_type
-- ----------------------------
DROP TABLE IF EXISTS `lp_material_price_type`;
CREATE TABLE `lp_material_price_type` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `row_no` int DEFAULT NULL,
  `bill_no` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `material_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_spec` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_model` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_shape` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `price_type` varchar(32) COLLATE utf8mb3_bin NOT NULL,
  `period` char(7) COLLATE utf8mb3_bin NOT NULL,
  `source` varchar(32) COLLATE utf8mb3_bin DEFAULT 'import',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_bill_no` (`bill_no`),
  KEY `idx_material_code` (`material_code`),
  KEY `idx_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_material_price_type
-- ----------------------------
BEGIN;
INSERT INTO `lp_material_price_type` (`id`, `row_no`, `bill_no`, `material_code`, `material_name`, `material_spec`, `material_model`, `material_shape`, `price_type`, `period`, `source`, `created_at`, `updated_at`) VALUES (1, 1, 'JG202601010001', '1008000300944', '阀体部件', 'RFG-K04-002784', 'RFG-K04-002784', '制造件', '联动价', '2026-02', 'import', '2026-02-10 14:58:29', '2026-02-10 14:58:29');
INSERT INTO `lp_material_price_type` (`id`, `row_no`, `bill_no`, `material_code`, `material_name`, `material_spec`, `material_model`, `material_shape`, `price_type`, `period`, `source`, `created_at`, `updated_at`) VALUES (2, 2, 'JG202601010001', '1008000300950', '调节部件', 'RFGN-14003', 'RFGN-14003', '采购件', '联动价', '2026-02', 'import', '2026-02-10 14:58:29', '2026-02-10 14:58:29');
INSERT INTO `lp_material_price_type` (`id`, `row_no`, `bill_no`, `material_code`, `material_name`, `material_spec`, `material_model`, `material_shape`, `price_type`, `period`, `source`, `created_at`, `updated_at`) VALUES (3, 3, 'JG202601010001', '1008000300939', '阀芯部件', 'RFGC-23158', 'RFGC-23158', '采购件', '联动价', '2026-02', 'import', '2026-02-10 14:58:29', '2026-02-10 14:58:29');
INSERT INTO `lp_material_price_type` (`id`, `row_no`, `bill_no`, `material_code`, `material_name`, `material_spec`, `material_model`, `material_shape`, `price_type`, `period`, `source`, `created_at`, `updated_at`) VALUES (4, 4, 'JG202601010001', '9990000056820', '端子', 'MOLEX 0850-0031', 'MOLEX 0850-0031', '采购件', '固定价', '2026-02', 'import', '2026-02-10 14:58:29', '2026-02-10 14:58:29');
INSERT INTO `lp_material_price_type` (`id`, `row_no`, `bill_no`, `material_code`, `material_name`, `material_spec`, `material_model`, `material_shape`, `price_type`, `period`, `source`, `created_at`, `updated_at`) VALUES (5, 5, 'JG202601010001', '250012748', '瓦楞纸箱', 'BZ-RFG-F05001-02_外*425*275*275', '', '采购件', '固定价', '2026-02', 'import', '2026-02-10 14:58:29', '2026-02-10 14:58:29');
COMMIT;

-- ----------------------------
-- Table structure for lp_other_expense_rate
-- ----------------------------
DROP TABLE IF EXISTS `lp_other_expense_rate`;
CREATE TABLE `lp_other_expense_rate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_code` varchar(80) NOT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `spec` varchar(120) DEFAULT NULL,
  `model` varchar(120) DEFAULT NULL,
  `customer` varchar(120) DEFAULT NULL,
  `expense_type` varchar(80) NOT NULL,
  `expense_amount` decimal(14,6) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_other_expense_unique` (`material_code`,`customer`,`expense_type`),
  KEY `idx_other_expense_material` (`material_code`),
  KEY `idx_other_expense_product` (`product_name`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_other_expense_rate
-- ----------------------------
BEGIN;
INSERT INTO `lp_other_expense_rate` (`id`, `material_code`, `product_name`, `spec`, `model`, `customer`, `expense_type`, `expense_amount`, `created_at`, `updated_at`) VALUES (1, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 'A客户', '认证费', 10.000000, '2026-02-06 10:07:11', '2026-02-06 10:07:11');
INSERT INTO `lp_other_expense_rate` (`id`, `material_code`, `product_name`, `spec`, `model`, `customer`, `expense_type`, `expense_amount`, `created_at`, `updated_at`) VALUES (2, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', 'A客户', '模具费', 5.000000, '2026-02-06 10:07:11', '2026-02-06 10:07:11');
INSERT INTO `lp_other_expense_rate` (`id`, `material_code`, `product_name`, `spec`, `model`, `customer`, `expense_type`, `expense_amount`, `created_at`, `updated_at`) VALUES (3, '1008900031000', '热力膨胀阀', 'RFG-K0000A-3222', 'RFGK19E-5.0A-3222', 'B客户', '模具费', 20.000000, '2026-02-06 10:07:11', '2026-02-06 10:07:11');
COMMIT;

-- ----------------------------
-- Table structure for lp_price_fixed_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_price_fixed_item`;
CREATE TABLE `lp_price_fixed_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `org_code` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `source_name` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `supplier_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `supplier_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `purchase_class` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `spec_model` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `unit` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL,
  `formula_expr` varchar(512) COLLATE utf8mb3_bin DEFAULT NULL,
  `blank_weight` decimal(18,6) DEFAULT NULL,
  `net_weight` decimal(18,6) DEFAULT NULL,
  `process_fee` decimal(18,6) DEFAULT NULL,
  `agent_fee` decimal(18,6) DEFAULT NULL,
  `fixed_price` decimal(18,6) NOT NULL,
  `tax_included` tinyint NOT NULL DEFAULT '1',
  `effective_from` date DEFAULT NULL,
  `effective_to` date DEFAULT NULL,
  `order_type` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `quota` decimal(18,6) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_fixed_material` (`material_code`),
  KEY `idx_fixed_supplier` (`supplier_code`)
) ENGINE=InnoDB AUTO_INCREMENT=7 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_price_fixed_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_price_fixed_item` (`id`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `fixed_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (4, '210', '供管处', 'D材料有限公司Test', '1004', '部品联动', '端子', '9990000056820', 'MOLEX 0850-0031', '只', '', NULL, NULL, NULL, NULL, 2.000000, 1, NULL, NULL, '', 0.600000, '2026-02-04 22:00:26', '2026-02-04 22:00:26');
INSERT INTO `lp_price_fixed_item` (`id`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `fixed_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (5, '210', '供管处', 'E材料有限公司Test', '1005', '部品联动', '瓦楞纸箱', '250012748', 'BZ-RFG-F05001-02_外*425*275*275', '个', '', NULL, NULL, NULL, NULL, 1.000000, 1, NULL, NULL, '', 1.000000, '2026-02-04 22:00:26', '2026-02-04 22:00:26');
INSERT INTO `lp_price_fixed_item` (`id`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `fixed_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (6, '210', '供管处', 'F材料有限公司Test', '1006', '部品联动', '端子', '9990000056820', 'MOLEX 0850-0031', '只', '', NULL, NULL, NULL, NULL, 3.000000, 1, NULL, NULL, '', 0.400000, '2026-02-04 22:00:26', '2026-02-04 22:00:26');
COMMIT;

-- ----------------------------
-- Table structure for lp_price_linked_calc_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_price_linked_calc_item`;
CREATE TABLE `lp_price_linked_calc_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `item_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `shape_attr` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `bom_qty` decimal(18,2) DEFAULT NULL,
  `part_unit_price` decimal(18,2) DEFAULT NULL,
  `part_amount` decimal(18,2) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_pl_calc_oa` (`oa_no`),
  KEY `idx_pl_calc_item` (`item_code`),
  KEY `idx_pl_calc_shape` (`shape_attr`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_price_linked_calc_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_price_linked_calc_item` (`id`, `oa_no`, `item_code`, `shape_attr`, `bom_qty`, `part_unit_price`, `part_amount`, `created_at`, `updated_at`) VALUES (1, 'FI-SR-006-20260116-0527', '1008000300939', '采购件', 1.00, 10.67, 10.67, '2026-02-05 13:50:47', '2026-02-05 13:50:47');
INSERT INTO `lp_price_linked_calc_item` (`id`, `oa_no`, `item_code`, `shape_attr`, `bom_qty`, `part_unit_price`, `part_amount`, `created_at`, `updated_at`) VALUES (2, 'FI-SR-006-20260116-0527', '1008000300950', '采购件', 1.00, 0.13, 0.13, '2026-02-05 13:50:47', '2026-02-05 13:50:47');
INSERT INTO `lp_price_linked_calc_item` (`id`, `oa_no`, `item_code`, `shape_attr`, `bom_qty`, `part_unit_price`, `part_amount`, `created_at`, `updated_at`) VALUES (3, 'FI-SR-006-20260116-0527', '1008000300944', '制造件', 1.00, 36.86, 36.86, '2026-02-05 13:50:47', '2026-02-05 13:50:47');
COMMIT;

-- ----------------------------
-- Table structure for lp_price_linked_item
-- ----------------------------
DROP TABLE IF EXISTS `lp_price_linked_item`;
CREATE TABLE `lp_price_linked_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `pricing_month` char(7) COLLATE utf8mb3_bin NOT NULL,
  `org_code` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `source_name` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `supplier_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `supplier_code` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `purchase_class` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_name` varchar(128) COLLATE utf8mb3_bin DEFAULT NULL,
  `material_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `spec_model` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `unit` varchar(16) COLLATE utf8mb3_bin DEFAULT NULL,
  `formula_expr` varchar(512) COLLATE utf8mb3_bin DEFAULT NULL,
  `formula_expr_cn` varchar(512) COLLATE utf8mb3_bin DEFAULT NULL,
  `blank_weight` decimal(18,6) DEFAULT NULL,
  `net_weight` decimal(18,6) DEFAULT NULL,
  `process_fee` decimal(18,6) DEFAULT NULL,
  `agent_fee` decimal(18,6) DEFAULT NULL,
  `manual_price` decimal(18,6) DEFAULT NULL,
  `tax_included` tinyint NOT NULL DEFAULT '1',
  `effective_from` date NOT NULL,
  `effective_to` date DEFAULT NULL,
  `order_type` varchar(32) COLLATE utf8mb3_bin DEFAULT NULL,
  `quota` decimal(18,6) DEFAULT NULL,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_linked_material` (`material_code`),
  KEY `idx_linked_month` (`pricing_month`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_price_linked_item
-- ----------------------------
BEGIN;
INSERT INTO `lp_price_linked_item` (`id`, `pricing_month`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `formula_expr_cn`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `manual_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (9, '2026-02', '210', '供管处', 'A材料有限公司Test', '1001', '部品联动', '阀体部件', '1008000300944', 'RFG-K04-002784', '只', '(Cu*0.59*1.02+Zn*0.41*1.03+1.45)*blank_weight-(blank_weight-net_weight)*(Cu*0.59+Zn*0.41)*0.915+process_fee', '(铜基价*0.59*1.02+锌基价*0.41*1.03+1.45)*下料重量-(下料重量-产品净重)*(铜基价*0.59+锌基价*0.41)*0.915+加工费', 667.000000, 367.000000, 7.774400, NULL, 29.419292, 0, '2025-10-31', '2025-11-29', 'VMI采购', 1.000000, '2026-02-05 16:55:44', '2026-02-05 16:55:44');
INSERT INTO `lp_price_linked_item` (`id`, `pricing_month`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `formula_expr_cn`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `manual_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (10, '2026-02', '210', '供管处', 'B材料有限公司Test', '1002', '部品联动', '调节部件', '1008000300950', 'RFGN-14003', '只', '((Cu*0.15+Zn*0.1+us_brass_price*0.75*1.05)*1.02+0.1+process_fee)*net_weight/1000', '((铜基价*0.15+锌基价*0.1+美国柜装黄铜价格*0.75*1.05)*1.02+0.1+加工费)*产品净重/1000', NULL, 5873.000000, 5.000000, NULL, 330.687965, 0, '2025-10-31', '2025-11-29', '标准采购      VMI采购', 1.000000, '2026-02-05 16:55:44', '2026-02-05 16:55:44');
INSERT INTO `lp_price_linked_item` (`id`, `pricing_month`, `org_code`, `source_name`, `supplier_name`, `supplier_code`, `purchase_class`, `material_name`, `material_code`, `spec_model`, `unit`, `formula_expr`, `formula_expr_cn`, `blank_weight`, `net_weight`, `process_fee`, `agent_fee`, `manual_price`, `tax_included`, `effective_from`, `effective_to`, `order_type`, `quota`, `created_at`, `updated_at`) VALUES (11, '2026-02', '210', '供管处', 'C材料有限公司Test', '1003', '部品联动', '阀芯部件', '1008000300939', 'RFGC-23158', '只', '(Cu*0.59*1.02+Zn*0.41*1.03+1.45)*blank_weight-(blank_weight-net_weight)*(Cu*0.59+Zn*0.41)*0.915+process_fee', '(铜基价*0.59*1.02+锌基价*0.41*1.03+1.45)*下料重量-(下料重量-产品净重)*(铜基价*0.59+锌基价*0.41)*0.915+加工费', 100.000000, 60.000000, 6.000000, NULL, 10.000000, 0, '2025-10-31', '2025-11-29', 'VMI采购', 1.000000, '2026-02-05 16:55:44', '2026-02-05 16:55:44');
COMMIT;

-- ----------------------------
-- Table structure for lp_price_variable
-- ----------------------------
DROP TABLE IF EXISTS `lp_price_variable`;
CREATE TABLE `lp_price_variable` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `variable_code` varchar(64) COLLATE utf8mb3_bin NOT NULL,
  `variable_name` varchar(128) COLLATE utf8mb3_bin NOT NULL,
  `source_type` varchar(32) COLLATE utf8mb3_bin NOT NULL,
  `source_table` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `source_field` varchar(64) COLLATE utf8mb3_bin DEFAULT NULL,
  `scope` varchar(32) COLLATE utf8mb3_bin DEFAULT '',
  `status` varchar(16) COLLATE utf8mb3_bin NOT NULL DEFAULT 'active',
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_variable_code` (`variable_code`)
) ENGINE=InnoDB AUTO_INCREMENT=14 DEFAULT CHARSET=utf8mb3 COLLATE=utf8mb3_bin;

-- ----------------------------
-- Records of lp_price_variable
-- ----------------------------
BEGIN;
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (1, 'Cu', '铜基价', 'OA_FORM', 'oa_form', 'copper_price', 'GLOBAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (2, 'Zn', '锌基价', 'OA_FORM', 'oa_form', 'zinc_price', 'GLOBAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (3, 'Al', '铝基价', 'OA_FORM', 'oa_form', 'aluminum_price', 'GLOBAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (4, 'Sn', '不锈钢基价', 'OA_FORM', 'oa_form', 'steel_price', 'GLOBAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (5, 'Cn', '其他材料价', 'OA_FORM', 'lp_oa_form', 'other_material', 'GLOBAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (6, 'blank_weight', '下料重量', 'LINKED_ITEM', 'lp_price_linked_item', 'blank_weight', 'MATERIAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (7, 'net_weight', '产品净重', 'LINKED_ITEM', 'lp_price_linked_item', 'net_weight', 'MATERIAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (8, 'process_fee', '加工费', 'LINKED_ITEM', 'lp_price_linked_item', 'process_fee', 'MATERIAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (9, 'agent_fee', '代理费', 'LINKED_ITEM', 'lp_price_linked_item', 'agent_fee', 'MATERIAL', 'active', '2026-02-04 04:02:31', '2026-02-04 04:02:31');
INSERT INTO `lp_price_variable` (`id`, `variable_code`, `variable_name`, `source_type`, `source_table`, `source_field`, `scope`, `status`, `created_at`, `updated_at`) VALUES (11, 'us_brass_price', '美国柜装黄铜价格', 'FACTOR', 'lp_finance_base_price', 'price', 'BASE_PRICE', 'active', '2026-02-04 04:38:08', '2026-02-04 04:38:08');
COMMIT;

-- ----------------------------
-- Table structure for lp_product_property
-- ----------------------------
DROP TABLE IF EXISTS `lp_product_property`;
CREATE TABLE `lp_product_property` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `level1_code` varchar(40) NOT NULL,
  `level1_name` varchar(120) NOT NULL,
  `parent_code` varchar(80) NOT NULL,
  `parent_name` varchar(120) DEFAULT NULL,
  `parent_spec` varchar(120) DEFAULT NULL,
  `parent_model` varchar(120) DEFAULT NULL,
  `period` varchar(7) NOT NULL,
  `product_attr` varchar(40) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_product_property_unique` (`level1_code`,`parent_code`,`period`),
  KEY `idx_product_property_level1` (`level1_name`),
  KEY `idx_product_property_parent` (`parent_code`)
) ENGINE=InnoDB AUTO_INCREMENT=5 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_product_property
-- ----------------------------
BEGIN;
INSERT INTO `lp_product_property` (`id`, `level1_code`, `level1_name`, `parent_code`, `parent_name`, `parent_spec`, `parent_model`, `period`, `product_attr`, `created_at`, `updated_at`) VALUES (3, '59', '四通阀事业部', '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', '2026-01', '非标品', '2026-02-10 15:50:40', '2026-02-10 15:50:40');
INSERT INTO `lp_product_property` (`id`, `level1_code`, `level1_name`, `parent_code`, `parent_name`, `parent_spec`, `parent_model`, `period`, `product_attr`, `created_at`, `updated_at`) VALUES (4, '59', '四通阀事业部', '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', '2026-02', '标准品', '2026-02-10 15:50:40', '2026-02-10 15:50:40');
COMMIT;

-- ----------------------------
-- Table structure for lp_quality_loss_rate
-- ----------------------------
DROP TABLE IF EXISTS `lp_quality_loss_rate`;
CREATE TABLE `lp_quality_loss_rate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `company` varchar(80) NOT NULL,
  `business_unit` varchar(80) NOT NULL,
  `product_category` varchar(80) NOT NULL,
  `product_subcategory` varchar(80) NOT NULL,
  `loss_rate` decimal(10,6) NOT NULL,
  `customer` varchar(80) DEFAULT NULL,
  `period` varchar(7) NOT NULL,
  `source_basis` varchar(255) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_quality_loss_unique` (`company`,`business_unit`,`product_category`,`product_subcategory`,`customer`,`period`),
  KEY `idx_quality_loss_period` (`period`),
  KEY `idx_quality_loss_company` (`company`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_quality_loss_rate
-- ----------------------------
BEGIN;
INSERT INTO `lp_quality_loss_rate` (`id`, `company`, `business_unit`, `product_category`, `product_subcategory`, `loss_rate`, `customer`, `period`, `source_basis`, `created_at`, `updated_at`) VALUES (1, '浙江三花商用制冷有限公司', '四通阀事业部', '热力膨胀阀', 'RFGF/RFGK', 0.010000, NULL, '2026-02', NULL, '2026-02-10 15:34:20', '2026-02-10 15:34:20');
INSERT INTO `lp_quality_loss_rate` (`id`, `company`, `business_unit`, `product_category`, `product_subcategory`, `loss_rate`, `customer`, `period`, `source_basis`, `created_at`, `updated_at`) VALUES (2, '浙江三花板换科技有限公司', '板换事业部', '板式换热器', '钎焊板式换热器', 0.020000, NULL, '2026-02', NULL, '2026-02-10 15:34:20', '2026-02-10 15:34:20');
COMMIT;

-- ----------------------------
-- Table structure for lp_salary_cost
-- ----------------------------
DROP TABLE IF EXISTS `lp_salary_cost`;
CREATE TABLE `lp_salary_cost` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `material_code` varchar(80) NOT NULL,
  `product_name` varchar(120) DEFAULT NULL,
  `spec` varchar(120) DEFAULT NULL,
  `model` varchar(120) DEFAULT NULL,
  `ref_material_code` varchar(80) DEFAULT NULL,
  `direct_labor_cost` decimal(14,6) NOT NULL,
  `indirect_labor_cost` decimal(14,6) NOT NULL,
  `source` varchar(40) DEFAULT NULL,
  `business_unit` varchar(80) DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_salary_unique` (`material_code`,`ref_material_code`,`business_unit`,`source`),
  KEY `idx_salary_material` (`material_code`),
  KEY `idx_salary_bu` (`business_unit`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_salary_cost
-- ----------------------------
BEGIN;
INSERT INTO `lp_salary_cost` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `direct_labor_cost`, `indirect_labor_cost`, `source`, `business_unit`, `created_at`, `updated_at`) VALUES (1, '1008900031271', '热力膨胀阀', 'RFG-K19060A-3128', 'RFGK19E-6.0A-3128', NULL, 1.000000, 2.000000, 'CMS', '四通阀事业部', '2026-02-05 22:07:30', '2026-02-05 22:07:30');
INSERT INTO `lp_salary_cost` (`id`, `material_code`, `product_name`, `spec`, `model`, `ref_material_code`, `direct_labor_cost`, `indirect_labor_cost`, `source`, `business_unit`, `created_at`, `updated_at`) VALUES (2, 'SH-001', NULL, NULL, NULL, '1008900031271', 1.000000, 2.000000, NULL, '四通阀事业部', '2026-02-05 22:07:30', '2026-02-05 22:07:30');
COMMIT;

-- ----------------------------
-- Table structure for lp_three_expense_rate
-- ----------------------------
DROP TABLE IF EXISTS `lp_three_expense_rate`;
CREATE TABLE `lp_three_expense_rate` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `company` varchar(120) NOT NULL,
  `business_unit` varchar(120) NOT NULL,
  `department` varchar(120) NOT NULL,
  `management_expense_rate` decimal(10,6) NOT NULL,
  `finance_expense_rate` decimal(10,6) NOT NULL,
  `sales_expense_rate` decimal(10,6) NOT NULL,
  `three_expense_rate_2025` decimal(10,6) NOT NULL,
  `three_expense_rate_2026` decimal(10,6) NOT NULL,
  `overseas_sales` varchar(10) DEFAULT NULL,
  `period` varchar(7) NOT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_three_expense_unique` (`company`,`business_unit`,`department`,`period`),
  KEY `idx_three_expense_dept` (`department`),
  KEY `idx_three_expense_period` (`period`)
) ENGINE=InnoDB AUTO_INCREMENT=11 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of lp_three_expense_rate
-- ----------------------------
BEGIN;
INSERT INTO `lp_three_expense_rate` (`id`, `company`, `business_unit`, `department`, `management_expense_rate`, `finance_expense_rate`, `sales_expense_rate`, `three_expense_rate_2025`, `three_expense_rate_2026`, `overseas_sales`, `period`, `created_at`, `updated_at`) VALUES (6, '浙江三花商用制冷有限公司', '电子产品事业部', '电子产品事业部', 0.030000, 0.030000, 0.030000, 0.090000, 0.150000, '是', '2026-02', '2026-02-10 15:42:54', '2026-02-10 15:42:54');
INSERT INTO `lp_three_expense_rate` (`id`, `company`, `business_unit`, `department`, `management_expense_rate`, `finance_expense_rate`, `sales_expense_rate`, `three_expense_rate_2025`, `three_expense_rate_2026`, `overseas_sales`, `period`, `created_at`, `updated_at`) VALUES (7, '浙江三花商用制冷有限公司', '四通阀事业部', '四通阀事业部', 0.080000, 0.020000, 0.050000, 0.150000, 0.150000, NULL, '2026-02', '2026-02-10 15:42:54', '2026-02-10 15:42:54');
INSERT INTO `lp_three_expense_rate` (`id`, `company`, `business_unit`, `department`, `management_expense_rate`, `finance_expense_rate`, `sales_expense_rate`, `three_expense_rate_2025`, `three_expense_rate_2026`, `overseas_sales`, `period`, `created_at`, `updated_at`) VALUES (8, '浙江三花板换科技有限公司', '板换事业部', '板换事业部', 0.080000, 0.020000, 0.050000, 0.150000, 0.150000, NULL, '2026-02', '2026-02-10 15:42:54', '2026-02-10 15:42:54');
INSERT INTO `lp_three_expense_rate` (`id`, `company`, `business_unit`, `department`, `management_expense_rate`, `finance_expense_rate`, `sales_expense_rate`, `three_expense_rate_2025`, `three_expense_rate_2026`, `overseas_sales`, `period`, `created_at`, `updated_at`) VALUES (9, '浙江三花商用制冷有限公司', '越南（商用）事业部', '越南（商用）事业部', 0.080000, 0.020000, 0.050000, 0.150000, 0.150000, NULL, '2026-02', '2026-02-10 15:42:54', '2026-02-10 15:42:54');
INSERT INTO `lp_three_expense_rate` (`id`, `company`, `business_unit`, `department`, `management_expense_rate`, `finance_expense_rate`, `sales_expense_rate`, `three_expense_rate_2025`, `three_expense_rate_2026`, `overseas_sales`, `period`, `created_at`, `updated_at`) VALUES (10, '浙江三花商用制冷有限公司', '？', '墨西哥', 0.080000, 0.020000, 0.050000, 0.150000, 0.150000, NULL, '2026-02', '2026-02-10 15:42:54', '2026-02-10 15:42:54');
COMMIT;

-- ----------------------------
-- Table structure for oa_form
-- ----------------------------
DROP TABLE IF EXISTS `oa_form`;
CREATE TABLE `oa_form` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_no` varchar(64) NOT NULL,
  `form_type` varchar(64) DEFAULT NULL,
  `apply_date` date DEFAULT NULL,
  `customer` varchar(255) DEFAULT NULL,
  `copper_price` decimal(12,2) DEFAULT NULL,
  `zinc_price` decimal(12,2) DEFAULT NULL,
  `aluminum_price` decimal(12,2) DEFAULT NULL,
  `steel_price` decimal(12,2) DEFAULT NULL,
  `other_material` decimal(12,2) DEFAULT NULL,
  `base_shipping` decimal(12,2) DEFAULT NULL,
  `sale_link` varchar(255) DEFAULT NULL,
  `remark` varchar(500) DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted` tinyint(1) DEFAULT '0',
  `calc_status` varchar(20) NOT NULL DEFAULT '未核算',
  `calc_at` datetime DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_oa_form_oa_no` (`oa_no`),
  KEY `idx_oa_form_calc_status` (`calc_status`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of oa_form
-- ----------------------------
BEGIN;
INSERT INTO `oa_form` (`id`, `oa_no`, `form_type`, `apply_date`, `customer`, `copper_price`, `zinc_price`, `aluminum_price`, `steel_price`, `other_material`, `base_shipping`, `sale_link`, `remark`, `created_at`, `updated_at`, `deleted`, `calc_status`, `calc_at`) VALUES (1, 'FI-SR-006-20260116-0527', '商用-FI-SR-005', '2026-01-16', 'YEONGJIN KOREA CO.,LTD.', 100000.00, 24662.00, NULL, NULL, NULL, 10615.00, '销售价格固定', '100000元/T 电解铜时成本', '2026-02-02 12:28:53', '2026-02-02 12:28:53', 0, '已核算', NULL);
COMMIT;

-- ----------------------------
-- Table structure for oa_form_item
-- ----------------------------
DROP TABLE IF EXISTS `oa_form_item`;
CREATE TABLE `oa_form_item` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `oa_form_id` bigint NOT NULL,
  `seq` int DEFAULT NULL,
  `product_name` varchar(255) DEFAULT NULL,
  `customer_drawing` varchar(255) DEFAULT NULL,
  `material_no` varchar(255) DEFAULT NULL,
  `sunl_model` varchar(255) DEFAULT NULL,
  `spec` varchar(255) DEFAULT NULL,
  `shipping_fee` decimal(12,4) DEFAULT NULL,
  `support_qty` decimal(12,4) DEFAULT NULL,
  `total_with_ship` decimal(12,4) DEFAULT NULL,
  `total_no_ship` decimal(12,4) DEFAULT NULL,
  `material_cost` decimal(12,4) DEFAULT NULL,
  `labor_cost` decimal(12,4) DEFAULT NULL,
  `manufacturing_cost` decimal(12,4) DEFAULT NULL,
  `management_cost` decimal(12,4) DEFAULT NULL,
  `valid_date` date DEFAULT NULL,
  `created_at` datetime DEFAULT NULL,
  `updated_at` datetime DEFAULT NULL,
  `deleted` tinyint(1) DEFAULT '0',
  PRIMARY KEY (`id`),
  KEY `idx_oa_form_id` (`oa_form_id`),
  KEY `idx_oa_form_material` (`oa_form_id`,`material_no`),
  KEY `idx_oa_form_drawing` (`oa_form_id`,`customer_drawing`),
  KEY `idx_oa_form_model_spec` (`oa_form_id`,`sunl_model`,`spec`)
) ENGINE=InnoDB AUTO_INCREMENT=3 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ----------------------------
-- Records of oa_form_item
-- ----------------------------
BEGIN;
INSERT INTO `oa_form_item` (`id`, `oa_form_id`, `seq`, `product_name`, `customer_drawing`, `material_no`, `sunl_model`, `spec`, `shipping_fee`, `support_qty`, `total_with_ship`, `total_no_ship`, `material_cost`, `labor_cost`, `manufacturing_cost`, `management_cost`, `valid_date`, `created_at`, `updated_at`, `deleted`) VALUES (1, 1, 1, '热力膨胀阀', '', '1008900031271', '型号A', '规格A', 0.3000, 12.0000, 5.2600, 4.9600, 2.1000, 0.6000, 0.8000, 0.4000, '2026-12-31', '2026-02-02 12:32:02', '2026-02-02 12:32:02', 0);
COMMIT;

SET FOREIGN_KEY_CHECKS = 1;
