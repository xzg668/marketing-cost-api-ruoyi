-- T10：新技术资料补录审核、审核继承和菜单切换。
SET NAMES utf8mb4;

ALTER TABLE `lp_quote_tech_review_item`
  ADD COLUMN `inherited_from_review_item_id` BIGINT NULL
    COMMENT '重提时继承的上一轮审核项ID' AFTER `decision_reason`,
  ADD COLUMN `row_version` INT NOT NULL DEFAULT 0
    COMMENT '审核项乐观锁' AFTER `decided_at`,
  ADD KEY `idx_quote_tech_review_inherited` (`inherited_from_review_item_id`),
  ADD CONSTRAINT `fk_quote_tech_review_inherited`
    FOREIGN KEY (`inherited_from_review_item_id`)
    REFERENCES `lp_quote_tech_review_item` (`id`) ON DELETE RESTRICT ON UPDATE RESTRICT,
  ADD CONSTRAINT `ck_quote_tech_review_row_version` CHECK (`row_version` >= 0),
  ADD CONSTRAINT `ck_quote_tech_review_inheritance` CHECK (
    (`inherited_from_review_item_id` IS NULL)
    OR (`decision` = 'PASSED'));

SET @technical_data_parent := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=0 AND path='collaboration'
   ORDER BY menu_id LIMIT 1
);

SET @technical_data_review_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_parent AND path='finance-reviews'
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='补录审核',component='technical-data/reviews/index',
       perms='technical:data:review:list',
       remark='按产品模块审核新技术资料不可变提交版本',
       update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_review_menu;

SET @technical_data_review_decide_menu := (
  SELECT menu_id FROM sys_menu
   WHERE parent_id=@technical_data_review_menu
     AND perms IN ('collaboration:review:decide','technical:data:review:decide')
   ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
   SET menu_name='技术资料审核处理',perms='technical:data:review:decide',
       remark='通过或退回技术资料产品模块',update_by='system',update_time=NOW()
 WHERE menu_id=@technical_data_review_decide_menu;

INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料审核处理',@technical_data_review_menu,1,'#',NULL,1,'0','F','1','0',
       'technical:data:review:decide','#','system',NOW(),'system',NOW(),
       '通过或退回技术资料产品模块',NULL
 WHERE @technical_data_review_menu IS NOT NULL
   AND @technical_data_review_decide_menu IS NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:review:decide');

SET @technical_data_review_decide_menu := (
  SELECT menu_id FROM sys_menu WHERE perms='technical:data:review:decide'
  ORDER BY menu_id LIMIT 1
);

INSERT IGNORE INTO sys_role_menu (role_id,menu_id)
SELECT existing.role_id,@technical_data_review_decide_menu
  FROM sys_role_menu existing
 WHERE existing.menu_id=@technical_data_review_menu
   AND @technical_data_review_decide_menu IS NOT NULL;
