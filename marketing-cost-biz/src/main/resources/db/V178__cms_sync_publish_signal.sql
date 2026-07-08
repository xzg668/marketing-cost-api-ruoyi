CREATE TABLE IF NOT EXISTS cms_sync_publish_signal (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键ID',
  batch_no VARCHAR(128) NOT NULL COMMENT '同步发布批次号，例如 CMS_20260707_COMMERCIAL',
  data_date DATE DEFAULT NULL COMMENT '数据日期',
  cost_year INT NOT NULL COMMENT '成本年度，例如2026',
  business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL/HOUSEHOLD',
  status VARCHAR(32) NOT NULL COMMENT '状态：READY/RUNNING/SUCCESS/FAILED/SKIPPED',
  message VARCHAR(1000) DEFAULT NULL COMMENT '执行消息或失败原因',
  ready_at DATETIME DEFAULT NULL COMMENT 'EasyData同步完成并发出信号时间',
  started_at DATETIME DEFAULT NULL COMMENT '发布任务开始时间',
  finished_at DATETIME DEFAULT NULL COMMENT '发布任务完成时间',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_cms_sync_publish_batch (batch_no),
  KEY idx_cms_sync_publish_status_ready (status, ready_at),
  KEY idx_cms_sync_publish_year_bu (cost_year, business_unit_type),
  KEY idx_cms_sync_publish_data_date (data_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='CMS每日同步发布信号表';
