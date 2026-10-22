-- 旧物料类型对照改为按月、组织的取价顺序。先备份并验证恢复，再停止旧服务执行。
-- 非空旧表需显式 SET @allow_material_price_type_reset=1；重复执行不清理新配置。
DROP PROCEDURE IF EXISTS v323_monthly_material_price_priority;
DELIMITER //
CREATE PROCEDURE v323_monthly_material_price_priority()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_material_price_type' AND column_name='price_org_code') THEN
    IF EXISTS (SELECT 1 FROM lp_material_price_type LIMIT 1)
        AND COALESCE(@allow_material_price_type_reset,0)<>1 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V323: verified backup and explicit old priority reset required';
    END IF;
    DELETE FROM lp_material_price_type;
    ALTER TABLE lp_material_price_type
      ADD COLUMN price_org_code VARCHAR(32) NOT NULL COMMENT '取价组织，不跨组织混用',
      ADD COLUMN manual_priority TINYINT NULL COMMENT '财务排序；NULL使用系统priority',
      ADD COLUMN priority_updated_by BIGINT NULL COMMENT '最后修改顺序的用户',
      ADD COLUMN priority_updated_at DATETIME(6) NULL COMMENT '最后修改或恢复默认时间',
      MODIFY COLUMN period CHAR(7) NOT NULL,
      MODIFY COLUMN business_unit_type VARCHAR(20) NOT NULL,
      DROP INDEX uk_route_period_mat_type_bu,
      ADD UNIQUE KEY uk_material_price_priority_scope
        (business_unit_type,period,price_org_code,material_code,price_type),
      ADD CONSTRAINT ck_material_price_priority CHECK
        (priority BETWEEN 1 AND 5 AND (manual_priority IS NULL OR manual_priority BETWEEN 1 AND 5));
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_prepare_item' AND column_name='price_org_code') THEN
    -- 历史组织保持未知，禁止由当前物料数据猜填历史结果。
    ALTER TABLE lp_price_prepare_item ADD COLUMN price_org_code VARCHAR(32) NULL
      COMMENT '本次取价组织；旧快照可能为空',
      ADD INDEX idx_prepare_material_scope
        (business_unit_type,period_month,price_org_code,material_code,id);
  END IF;
END//
DELIMITER ;
CALL v323_monthly_material_price_priority();
DROP PROCEDURE v323_monthly_material_price_priority;
