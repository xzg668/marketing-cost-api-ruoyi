-- PRC-06：最终招标条件独立于业务员初步确认字段。历史空值按非招标，不改历史冻结快照。
SET NAMES utf8mb4;
DROP PROCEDURE IF EXISTS v313_board_tender;
DELIMITER //
CREATE PROCEDURE v313_board_tender()
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
      WHERE table_schema=DATABASE() AND table_name='oa_form' AND column_name='board_tender') THEN
    ALTER TABLE oa_form ADD COLUMN board_tender TINYINT(1) NULL
      COMMENT '最终是否招标需求（仅针对板换），OA sfzbxqjzdbhcp，空按否';
  END IF;
END//
DELIMITER ;
CALL v313_board_tender();
DROP PROCEDURE v313_board_tender;
