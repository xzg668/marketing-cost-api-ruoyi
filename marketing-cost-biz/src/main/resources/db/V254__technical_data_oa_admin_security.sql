-- =============================================================================
-- V254: 技术资料补录 OA 协同、一次性短票和管理员受控操作
-- -----------------------------------------------------------------------------
-- 不新增长期令牌表。一次性短票的单次消费使用统一业务审计日志中的幂等键落账；
-- OA 回调顺序、重试状态和管理员代录锁均收敛在技术任务主表。
-- =============================================================================

SET NAMES utf8mb4;

ALTER TABLE `lp_quote_tech_task`
  ADD COLUMN `external_active_lock_key` VARCHAR(320)
    GENERATED ALWAYS AS (
      CASE
        WHEN `active_flag` = 1
          AND `external_system` IS NOT NULL
          AND `external_task_id` IS NOT NULL
        THEN CONCAT(`external_system`, ':', `external_task_id`)
        ELSE NULL
      END
    ) STORED COMMENT '活动任务外部身份唯一锁；历史任务为空' AFTER `external_task_id`,
  ADD COLUMN `external_callback_seq` BIGINT NOT NULL DEFAULT 0
    COMMENT '已受理OA回调的最大顺序号' AFTER `external_task_status`,
  ADD COLUMN `external_last_event_id` VARCHAR(128) DEFAULT NULL
    COMMENT '最近受理OA事件ID' AFTER `external_callback_seq`,
  ADD COLUMN `external_retry_count` INT NOT NULL DEFAULT 0
    COMMENT 'OA发布失败重试次数' AFTER `external_last_sync_at`,
  ADD COLUMN `external_next_retry_at` DATETIME DEFAULT NULL
    COMMENT 'OA下次允许重试时间' AFTER `external_retry_count`,
  ADD COLUMN `proxy_operator_user_id` BIGINT DEFAULT NULL
    COMMENT '当前受控代录管理员ID' AFTER `external_last_error`,
  ADD COLUMN `proxy_operator_name` VARCHAR(128) DEFAULT NULL
    COMMENT '当前受控代录管理员姓名' AFTER `proxy_operator_user_id`,
  ADD COLUMN `proxy_reason` VARCHAR(500) DEFAULT NULL
    COMMENT '受控代录原因' AFTER `proxy_operator_name`,
  ADD COLUMN `proxy_request_id` VARCHAR(128) DEFAULT NULL
    COMMENT '受控代录请求ID' AFTER `proxy_reason`,
  ADD COLUMN `proxy_started_at` DATETIME DEFAULT NULL
    COMMENT '受控代录开始时间' AFTER `proxy_request_id`,
  ADD UNIQUE KEY `uk_quote_tech_task_external_identity` (`external_active_lock_key`),
  ADD CONSTRAINT `ck_quote_tech_task_external_counter`
    CHECK (`external_callback_seq` >= 0 AND `external_retry_count` >= 0),
  ADD CONSTRAINT `ck_quote_tech_task_proxy_binding` CHECK (
    (`proxy_operator_user_id` IS NULL AND `proxy_operator_name` IS NULL
      AND `proxy_reason` IS NULL AND `proxy_request_id` IS NULL AND `proxy_started_at` IS NULL)
    OR
    (`proxy_operator_user_id` IS NOT NULL AND `proxy_operator_name` IS NOT NULL
      AND `proxy_reason` IS NOT NULL AND `proxy_request_id` IS NOT NULL
      AND `proxy_started_at` IS NOT NULL)
  );

ALTER TABLE `lp_business_change_log`
  ADD COLUMN `idempotency_key` VARCHAR(190) DEFAULT NULL
    COMMENT '需要严格单次落账事件的全局幂等键' AFTER `request_id`,
  ADD UNIQUE KEY `uk_business_change_idempotency` (`idempotency_key`);

SET @technical_data_task_menu := (
  SELECT menu_id FROM sys_menu
   WHERE perms='technical:data:task:list'
   ORDER BY menu_id LIMIT 1
);

-- 独立授权点：普通任务管理员无需获得超级管理员通配权限。
INSERT INTO sys_menu
  (menu_name,parent_id,order_num,path,component,is_frame,is_cache,menu_type,visible,status,
   perms,icon,create_by,create_time,update_by,update_time,remark,business_unit_type)
SELECT '技术资料任务管理',@technical_data_task_menu,2,'#',NULL,1,'0','F','1','0',
       'technical:data:admin:operate','#','system',NOW(),'system',NOW(),
       '改派、受控代录、解锁、OA重试、作废和短票签发',NULL
 WHERE @technical_data_task_menu IS NOT NULL
   AND NOT EXISTS (SELECT 1 FROM sys_menu WHERE perms='technical:data:admin:operate');
