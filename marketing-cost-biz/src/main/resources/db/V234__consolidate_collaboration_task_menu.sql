-- 协作入口收敛：一个技术任务清单，报价核算发起人负责后续补录审核。
SET NAMES utf8mb4;

SET @collaboration_parent := (
  SELECT menu_id FROM sys_menu
  WHERE parent_id=0 AND path='collaboration'
  ORDER BY menu_id LIMIT 1
);

SET @technical_task_menu := (
  SELECT menu_id FROM sys_menu
  WHERE parent_id=@collaboration_parent AND component='collaboration/technical/index' AND path='tasks'
  ORDER BY menu_id LIMIT 1
);

SET @obsolete_price_menu := (
  SELECT menu_id FROM sys_menu
  WHERE parent_id=@collaboration_parent AND component='collaboration/technical/index' AND path='prices'
  ORDER BY menu_id LIMIT 1
);

SET @finance_review_menu := (
  SELECT menu_id FROM sys_menu
  WHERE parent_id=@collaboration_parent AND component='collaboration/finance/index'
  ORDER BY menu_id LIMIT 1
);

SET @finance_review_decide := (
  SELECT menu_id FROM sys_menu
  WHERE perms='collaboration:review:decide'
  ORDER BY menu_id LIMIT 1
);

UPDATE sys_menu
SET menu_name='协作任务', remark='产品补录任务与报价补录审核',
    update_by='system', update_time=NOW()
WHERE menu_id=@collaboration_parent;

UPDATE sys_menu
SET menu_name='我的协作任务', order_num=1,
    remark='本人负责的BOM、包装和底层价格共用一个任务清单',
    update_by='system', update_time=NOW()
WHERE menu_id=@technical_task_menu;

-- 原“补价协作”只是同一任务清单的筛选入口；删除菜单及授权，避免双入口继续增长。
DELETE FROM sys_role_menu WHERE menu_id=@obsolete_price_menu;
DELETE FROM sys_menu WHERE menu_id=@obsolete_price_menu;

UPDATE sys_menu
SET menu_name='补录审核', path='finance-reviews', order_num=2,
    remark='核算发起人审核本人报价的技术补录内容',
    update_by='system', update_time=NOW()
WHERE menu_id=@finance_review_menu;

-- 修复迁移前已创建但尚未进入审核的任务：创建人就是当时的核算发起人。
UPDATE lp_quote_collaboration_task
SET finance_reviewer_user_id=created_by,
    finance_reviewer_name=created_by_name,
    updated_by=created_by,
    updated_by_name=created_by_name,
    updated_at=NOW()
WHERE finance_reviewer_user_id IS NULL
  AND created_by IS NOT NULL AND created_by > 0
  AND master_status IN ('WAIT_TECH', 'WAIT_FINANCE', 'PARTIAL_RETURN');

-- 报价人员是核算发起人，也是本次补录的审核责任人；服务端仍按 reviewer_user_id 校验本人任务。
INSERT IGNORE INTO sys_role_menu (role_id, menu_id)
SELECT role.role_id, menu.menu_id
FROM sys_role role
JOIN sys_menu menu ON menu.menu_id IN (
  @collaboration_parent,
  @finance_review_menu,
  @finance_review_decide
)
WHERE LOWER(role.role_key) IN ('bu_staff', 'bu_director')
  AND role.status='0' AND role.del_flag='0';
