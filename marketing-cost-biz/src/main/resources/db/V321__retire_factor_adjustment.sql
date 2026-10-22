-- 退出旧影响因素调价页面及批次。执行前必须完成备份与恢复验证，并停用旧版本服务。
-- 财务发布、技术私有月价、月价变更日志和月度重算业务继续保留。
-- 若其他环境仍有旧批次或非空引用，停止迁移，不能直接抹去历史数据。
DROP PROCEDURE IF EXISTS v321_retire_factor_adjustment;
DELIMITER //
CREATE PROCEDURE v321_retire_factor_adjustment()
BEGIN
  DECLARE finished INT DEFAULT 0;
  DECLARE old_table VARCHAR(64);
  DECLARE old_column VARCHAR(64);
  DECLARE finance_menu BIGINT;
  DECLARE obsolete_columns CURSOR FOR
    SELECT table_name, column_name FROM information_schema.columns
    WHERE table_schema=DATABASE() AND (
      (table_name IN ('lp_monthly_reprice_batch','lp_cost_run_task',
                     'lp_price_linked_calc_item','lp_factor_monthly_price_change_log')
        AND column_name='adjust_batch_id')
      OR (table_name='lp_factor_monthly_price' AND column_name IN
        ('latest_adjust_batch_id','latest_adjust_source_type','latest_adjusted_by','latest_adjusted_at')));
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET finished=1;

  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name='lp_factor_adjust_batch') THEN
    IF EXISTS (SELECT 1 FROM lp_factor_adjust_batch LIMIT 1) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V321: old adjustment batches need archival migration';
    END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema=DATABASE()
      AND table_name='lp_factor_adjust_price') THEN
    IF EXISTS (SELECT 1 FROM lp_factor_adjust_price LIMIT 1) THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V321: old adjustment prices need archival migration';
    END IF;
  END IF;
  IF EXISTS (SELECT 1 FROM lp_price_linked_calc_item WHERE factor_source='ADJUST_BATCH' LIMIT 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V321: old adjustment calculation results need archival migration';
  END IF;
  OPEN obsolete_columns;
  validate_columns: LOOP
    FETCH obsolete_columns INTO old_table, old_column;
    IF finished=1 THEN LEAVE validate_columns; END IF;
    SET @v321_sql=CONCAT('SELECT COUNT(*) INTO @v321_values FROM `',old_table,
      '` WHERE `',old_column,'` IS NOT NULL');
    PREPARE v321_stmt FROM @v321_sql;
    EXECUTE v321_stmt;
    DEALLOCATE PREPARE v321_stmt;
    IF @v321_values>0 THEN
      SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V321: nonempty old adjustment references need archival migration';
    END IF;
  END LOOP;
  CLOSE obsolete_columns;

  SELECT MAX(menu_id) INTO finance_menu FROM sys_menu WHERE component='pages:FinanceCuBasePricePage';
  IF finance_menu IS NULL AND EXISTS (SELECT 1 FROM sys_menu
      WHERE perms IN ('price:finance-base:list','price:finance-base:import')) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='V321: finance menu missing; cannot preserve shared permissions';
  END IF;

  -- 原唯一键含始终为空的旧批次ID。月度结果改按取价时点区分，报价结果仍使用原报价唯一键。
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND column_name='monthly_result_key') THEN
    ALTER TABLE lp_price_linked_calc_item ADD COLUMN monthly_result_key VARCHAR(32)
      GENERATED ALWAYS AS (IF(calc_scene='MONTHLY_ADJUST',calc_scene,NULL)) STORED;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_monthly_as_of') THEN
    ALTER TABLE lp_price_linked_calc_item ADD UNIQUE KEY uk_pl_calc_monthly_as_of
      (business_unit_type,monthly_result_key,item_code,pricing_month,price_as_of_time,
       source_kind,price_org_code,technical_input_key);
  END IF;
  IF EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
      AND table_name='lp_price_linked_calc_item' AND index_name='uk_pl_calc_adjust_scene') THEN
    ALTER TABLE lp_price_linked_calc_item DROP INDEX uk_pl_calc_adjust_scene;
  END IF;
  SET finished=0;
  OPEN obsolete_columns;
  remove_columns: LOOP
    FETCH obsolete_columns INTO old_table, old_column;
    IF finished=1 THEN LEAVE remove_columns; END IF;
    SET @v321_sql=CONCAT('ALTER TABLE `',old_table,'` DROP COLUMN `',old_column,'`');
    PREPARE v321_stmt FROM @v321_sql;
    EXECUTE v321_stmt;
    DEALLOCATE PREPARE v321_stmt;
  END LOOP;
  CLOSE obsolete_columns;

  -- 查询、导入仍由财务基价使用，保留原权限ID及角色授权，仅调整所属菜单。
  UPDATE sys_menu SET parent_id=finance_menu,
    menu_name=IF(perms='price:finance-base:list','财务基价查询','财务基价导入')
    WHERE perms IN ('price:finance-base:list','price:finance-base:import');
  DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id
    WHERE m.component IN ('pages:PriceLinkedFinanceBasePage','rate/finance-base-price/index',
                          'price/linked/finance-base/index')
      OR m.path='/price/linked/finance-base' OR m.perms LIKE 'price:factor-adjust:%'
      OR m.perms IN ('price:finance-base:add','price:finance-base:edit','price:finance-base:remove');
  DELETE FROM sys_menu
    WHERE component IN ('pages:PriceLinkedFinanceBasePage','rate/finance-base-price/index',
                         'price/linked/finance-base/index')
      OR path='/price/linked/finance-base' OR perms LIKE 'price:factor-adjust:%'
      OR perms IN ('price:finance-base:add','price:finance-base:edit','price:finance-base:remove');
  DROP TABLE IF EXISTS lp_factor_adjust_price;
  DROP TABLE IF EXISTS lp_factor_adjust_batch;
END//
DELIMITER ;
CALL v321_retire_factor_adjustment();
DROP PROCEDURE v321_retire_factor_adjustment;
