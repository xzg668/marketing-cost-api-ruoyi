-- 规则配置 -> 成品零件成本配置；只向实际 admin / bu_director 角色开放。
INSERT INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,
  visible,status,perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
VALUES(40560,'成品零件成本配置',40475,16,'/rules/product-part-cost','pages:ProductPartCostRulePage',1,'0','C',
  '0','0','rules:product-part-cost:list','#','admin',NOW(),'',NOW(),'成品指定零件单独计费配置',NULL),
  (40561,'零件成本配置查询',40560,1,'',NULL,1,'0','F','0','0','rules:product-part-cost:list','#','admin',NOW(),'',NOW(),'',NULL),
  (40562,'零件成本配置新增',40560,2,'',NULL,1,'0','F','0','0','rules:product-part-cost:add','#','admin',NOW(),'',NOW(),'',NULL),
  (40563,'零件成本配置修改',40560,3,'',NULL,1,'0','F','0','0','rules:product-part-cost:edit','#','admin',NOW(),'',NOW(),'',NULL),
  (40564,'零件成本配置删除',40560,4,'',NULL,1,'0','F','0','0','rules:product-part-cost:remove','#','admin',NOW(),'',NOW(),'',NULL);
INSERT IGNORE INTO sys_role_menu(role_id,menu_id)
SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.menu_id IN(40475,40560,40561,40562,40563,40564)
WHERE LOWER(r.role_key) IN('admin','bu_director') AND r.status='0' AND r.del_flag='0';
