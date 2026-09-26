-- I06 按原 OA 资料待办和需求版本记录一次确认；不同产品、操作人及重复点击共用该轮记录。
CREATE TABLE lp_oa_material_confirmation (
  id CHAR(36) COLLATE utf8mb4_bin NOT NULL PRIMARY KEY,
  oa_form_id BIGINT NOT NULL,
  form_version BIGINT NOT NULL,
  material_work_item_id VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  actor_user_id BIGINT NOT NULL,
  employee_no VARCHAR(64) NOT NULL,
  request_key VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
  accounting_month CHAR(7) NOT NULL,
  status VARCHAR(24) NOT NULL,
  request_json JSON NOT NULL,
  readiness_json JSON NOT NULL,
  result_json JSON NULL,
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_oa_material_round (oa_form_id,form_version,material_work_item_id),
  CONSTRAINT ck_oa_material_status CHECK
    (status IN ('PREPARED','SENDING','SUCCESS','REJECTED','NOT_SENT','UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
