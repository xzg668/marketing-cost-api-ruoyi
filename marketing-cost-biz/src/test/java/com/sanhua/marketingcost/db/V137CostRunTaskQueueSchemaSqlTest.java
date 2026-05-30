package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V137 通用成本核算任务队列表")
class V137CostRunTaskQueueSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增通用批次表和任务表")
  void createsCostRunTaskQueueTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_cost_run_batch",
        "CREATE TABLE IF NOT EXISTS lp_cost_run_task");
  }

  @Test
  @DisplayName("批次表包含场景、来源、进度、快照和幂等约束")
  void batchContainsControlFieldsAndIndexes() {
    assertThat(SQL).contains(
        "batch_no VARCHAR(64) NOT NULL COMMENT '通用核算批次号'",
        "scene VARCHAR(32) NOT NULL COMMENT '核算场景：QUOTE/MONTHLY_REPRICE'",
        "source_no VARCHAR(64) NOT NULL COMMENT '来源单号：普通报价为oa_no，月度调价为reprice_no'",
        "status VARCHAR(32) NOT NULL DEFAULT 'PENDING'",
        "progress INT NOT NULL DEFAULT 0 COMMENT '总进度 0-100'",
        "request_snapshot_json JSON DEFAULT NULL COMMENT '发起时关键参数快照'",
        "result_summary_json JSON DEFAULT NULL COMMENT '结果摘要'",
        "UNIQUE KEY uk_cost_run_batch_no (batch_no)",
        "UNIQUE KEY uk_cost_run_batch_source (scene, source_no, pricing_month, business_unit_type)",
        "KEY idx_cost_run_batch_status_scene (status, scene)");
  }

  @Test
  @DisplayName("任务表包含抢占、重试、错误和结果摘要字段")
  void taskContainsClaimRetryAndErrorFields() {
    assertThat(SQL).contains(
        "calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键'",
        "status VARCHAR(32) NOT NULL DEFAULT 'PENDING'",
        "worker_id VARCHAR(128) DEFAULT NULL COMMENT '当前持有Worker'",
        "locked_at DATETIME DEFAULT NULL COMMENT '锁定时间'",
        "lock_expire_time DATETIME DEFAULT NULL COMMENT '锁过期时间'",
        "retry_count INT NOT NULL DEFAULT 0 COMMENT '已重试次数'",
        "max_retry_count INT NOT NULL DEFAULT 3 COMMENT '最大重试次数'",
        "error_message VARCHAR(1000) DEFAULT NULL COMMENT '错误摘要'",
        "error_stack TEXT DEFAULT NULL COMMENT '错误堆栈摘要'",
        "UNIQUE KEY uk_cost_run_task_batch_object (batch_no, calc_object_key)",
        "KEY idx_cost_run_task_claim (status, lock_expire_time, id)",
        "KEY idx_cost_run_task_scene_claim (scene, status, lock_expire_time, id)",
        "KEY idx_cost_run_task_worker (worker_id, status)");
  }

  @Test
  @DisplayName("迁移不触碰现有普通报价和月度调价结果表")
  void doesNotTouchExistingResultTables() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_cost_run_result")
        .doesNotContain("ALTER TABLE lp_cost_run_result")
        .doesNotContain("ALTER TABLE lp_monthly_reprice_result");
  }

  private static String readSql() {
    try (var in = V137CostRunTaskQueueSchemaSqlTest.class.getResourceAsStream(
        "/db/V137__cost_run_task_queue_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V137 SQL 失败", e);
    }
  }
}
