-- PRC-05：保存原稿区间的财务输入、客户Cu、实际命中档和外推证据。
-- 只新增可空快照列，旧核算历史不回填或重算。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS v312_column;
DELIMITER //
CREATE PROCEDURE v312_column(IN t VARCHAR(64), IN c VARCHAR(64))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name=t AND column_name=c) THEN
    SET @v312_ddl=CONCAT('ALTER TABLE `',t,'` ADD COLUMN `',c,'` JSON NULL');
    PREPARE v312_stmt FROM @v312_ddl;
    EXECUTE v312_stmt;
    DEALLOCATE PREPARE v312_stmt;
  END IF;
END//
DELIMITER ;
CALL v312_column('lp_price_prepare_item', 'input_snapshot_json');
CALL v312_column('lp_make_part_price_calc_row', 'raw_input_snapshot_json');
CALL v312_column('lp_make_part_price_calc_row', 'scrap_input_snapshot_json');
DROP PROCEDURE v312_column;
