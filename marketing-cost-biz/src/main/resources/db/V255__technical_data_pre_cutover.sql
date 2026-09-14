-- T13：技术资料新链预切换。旧运行时代码和旧表到 T14/T15 才删除；本迁移先撤销
-- 所有旧写权限，配合 HTTP 只读门禁确保不再产生双写、双审核和双结果。
SET NAMES utf8mb4;

UPDATE sys_menu
   SET status='1',
       visible='1',
       update_by='system',
       update_time=NOW(),
       remark=CONCAT_WS('；', NULLIF(remark, ''), 'T13预切换：旧协作写权限已停用')
 WHERE perms IN (
   'collaboration:task:create',
   'collaboration:task:edit',
   'collaboration:task:submit',
   'collaboration:review:decide',
   'collaboration:operations:compensate'
 );

-- 新链菜单和权限必须保持可用，防止历史库升级时被同名旧菜单覆盖。
UPDATE sys_menu
   SET status='0', visible='0', update_by='system', update_time=NOW()
 WHERE perms IN (
   'technical:data:task:list',
   'technical:data:task:edit',
   'technical:data:review:list',
   'technical:data:review:decide',
   'technical:data:admin:operate'
 );
