ALTER TABLE `lp_quote_tech_aux_item`
  ADD COLUMN `auxiliary_material_no` VARCHAR(64) NULL COMMENT '辅料料号' AFTER `subject_name`,
  ADD COLUMN `auxiliary_spec` VARCHAR(255) NULL COMMENT '辅料规格' AFTER `auxiliary_name`,
  ADD COLUMN `price_unit` VARCHAR(32) NULL COMMENT '参考单价计价单位' AFTER `reference_unit_price`,
  ADD COLUMN `loss_rate` DECIMAL(12,8) NOT NULL DEFAULT 0 COMMENT '损耗率，小数口径' AFTER `price_unit`;

UPDATE `lp_quote_tech_aux_item`
   SET `auxiliary_material_no` = `subject_code`
 WHERE `auxiliary_material_no` IS NULL OR TRIM(`auxiliary_material_no`) = '';

UPDATE `lp_quote_tech_aux_item`
   SET `price_unit` = CONCAT('元/', `standard_unit`)
 WHERE `price_unit` IS NULL OR TRIM(`price_unit`) = '';

ALTER TABLE `lp_quote_tech_aux_item`
  MODIFY COLUMN `auxiliary_material_no` VARCHAR(64) NOT NULL COMMENT '辅料料号',
  MODIFY COLUMN `price_unit` VARCHAR(32) NOT NULL COMMENT '参考单价计价单位',
  ADD KEY `idx_quote_tech_aux_material` (`auxiliary_material_no`),
  ADD CONSTRAINT `ck_quote_tech_aux_loss_rate`
    CHECK (`loss_rate` >= 0 AND `loss_rate` <= 1);
