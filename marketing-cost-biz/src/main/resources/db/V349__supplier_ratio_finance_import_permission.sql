-- Reuse existing supplier-ratio read/import permissions and their menu ancestors.
-- Role data only: apply to explicitly authorized environments, never as structure DDL.
INSERT INTO sys_role_menu(role_id,menu_id)
WITH RECURSIVE ratio_menu AS (
  SELECT menu_id,parent_id FROM sys_menu
  WHERE perms IN ('base:supplier-supply-ratio:list','base:supplier-supply-ratio:import')
  UNION DISTINCT
  SELECT p.menu_id,p.parent_id FROM sys_menu p JOIN ratio_menu child ON child.parent_id=p.menu_id
)
SELECT r.role_id,m.menu_id FROM sys_role r CROSS JOIN ratio_menu m
WHERE r.role_key='finance_reviewer' AND r.status='0' AND r.del_flag='0'
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.role_id AND rm.menu_id=m.menu_id);
