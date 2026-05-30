package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V126 月度调价核心表结构")
class V126MonthlyRepriceCoreSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增 6 张月度调价核心表")
  void createsMonthlyRepriceTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_batch",
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_task",
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_result",
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_part_item",
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_cost_item",
        "CREATE TABLE IF NOT EXISTS lp_monthly_reprice_audit_log");
  }

  @Test
  @DisplayName("批次表保留业务单元锁、执行后端和确认统计字段")
  void batchContainsControlFields() {
    assertThat(SQL).contains(
        "reprice_no VARCHAR(64) NOT NULL COMMENT '月度调价批次号'",
        "pricing_month VARCHAR(7) NOT NULL COMMENT '调价月份 YYYY-MM'",
        "business_unit_type VARCHAR(32) NOT NULL COMMENT '业务单元'",
        "adjust_batch_id BIGINT NOT NULL COMMENT '影响因素调价批次ID lp_factor_adjust_batch.id'",
        "execution_backend VARCHAR(32) NOT NULL DEFAULT 'LOCAL_WORKER'",
        "status VARCHAR(32) NOT NULL DEFAULT 'CREATED'",
        "failed_count INT NOT NULL DEFAULT 0",
        "UNIQUE KEY uk_monthly_reprice_no");
  }

  @Test
  @DisplayName("任务和结果表用 reprice_no + calc_object_key 做唯一对象")
  void taskAndResultUseCalcObjectKey() {
    assertThat(SQL).contains(
        "calc_object_key VARCHAR(128) NOT NULL COMMENT '核算对象唯一键'",
        "UNIQUE KEY uk_reprice_task_object (reprice_no, calc_object_key)",
        "UNIQUE KEY uk_reprice_result_object (reprice_no, calc_object_key)",
        "package_method VARCHAR(128) DEFAULT NULL COMMENT '包装方式'",
        "customer_name VARCHAR(255) DEFAULT NULL COMMENT '客户名称'",
        "normalized_customer_name VARCHAR(255) DEFAULT NULL COMMENT '标准化客户名称'");
  }

  @Test
  @DisplayName("结果明细保留 BI 下钻需要的部品和成本项明细")
  void detailTablesContainDrilldownFields() {
    assertThat(SQL).contains(
        "lp_monthly_reprice_part_item",
        "part_code VARCHAR(64) DEFAULT NULL COMMENT '部品或物料编码'",
        "linked_calc_item_id BIGINT DEFAULT NULL COMMENT '联动价计算结果ID lp_price_linked_calc_item.id'",
        "lp_monthly_reprice_cost_item",
        "cost_item_code VARCHAR(64) NOT NULL COMMENT '成本项编码'",
        "UNIQUE KEY uk_monthly_cost_item (reprice_no, calc_object_key, cost_item_code)");
  }

  @Test
  @DisplayName("审计日志记录操作人、操作对象和前后快照")
  void auditLogContainsTraceFields() {
    assertThat(SQL).contains(
        "operation_type VARCHAR(64) NOT NULL COMMENT '操作类型'",
        "operator_id VARCHAR(64) DEFAULT NULL COMMENT '操作人ID'",
        "target_key VARCHAR(255) DEFAULT NULL COMMENT '操作对象业务键'",
        "before_json JSON DEFAULT NULL COMMENT '操作前快照'",
        "after_json JSON DEFAULT NULL COMMENT '操作后快照'",
        "request_id VARCHAR(128) DEFAULT NULL COMMENT '请求链路ID'");
  }

  @Test
  @DisplayName("影响因素调价批次增加 adjust_type 且历史默认 NORMAL")
  void addsFactorAdjustType() {
    assertThat(SQL).contains(
        "CALL add_column_if_not_exists_v126('lp_factor_adjust_batch', 'adjust_type'",
        "VARCHAR(16) NOT NULL DEFAULT ''NORMAL'' COMMENT ''调整类型：NORMAL普通维护/MONTHLY月度调价''",
        "idx_factor_adjust_type_context",
        "SET adjust_type = 'NORMAL'");
  }

  @Test
  @DisplayName("迁移幂等且不清空历史成本结果")
  void isIdempotentAndDoesNotTouchCostRunResultData() {
    assertThat(SQL)
        .contains("IF NOT EXISTS")
        .contains("add_column_if_not_exists_v126")
        .contains("add_index_if_not_exists_v126")
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_cost_run_result")
        .doesNotContain("old_cost")
        .doesNotContain("delta_cost");
  }

  private static String readSql() {
    try (var in = V126MonthlyRepriceCoreSchemaSqlTest.class.getResourceAsStream(
        "/db/V126__monthly_reprice_core_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V126 SQL 失败", e);
    }
  }
}

