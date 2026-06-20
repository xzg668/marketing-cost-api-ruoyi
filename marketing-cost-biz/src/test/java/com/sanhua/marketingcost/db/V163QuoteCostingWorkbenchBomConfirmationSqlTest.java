package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V163 单产品核算工作台 BOM 确认表")
class V163QuoteCostingWorkbenchBomConfirmationSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("创建 BOM 确认批次和日志表")
  void createsConfirmationTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_confirmation",
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_confirmation_log",
        "confirm_no VARCHAR(64) NOT NULL",
        "oa_form_item_id BIGINT NOT NULL",
        "top_product_code VARCHAR(64) NOT NULL",
        "period_month VARCHAR(7) NOT NULL");
  }

  @Test
  @DisplayName("确认批次字段覆盖工作台统计口径")
  void includesWorkbenchSummaryColumns() {
    assertThat(SQL).contains(
        "confirm_status VARCHAR(32) NOT NULL DEFAULT 'CONFIRMED'",
        "confirm_version INT NOT NULL DEFAULT 1",
        "row_count INT NOT NULL DEFAULT 0",
        "manual_modified_count INT NOT NULL DEFAULT 0",
        "replace_count INT NOT NULL DEFAULT 0",
        "usage_adjust_count INT NOT NULL DEFAULT 0",
        "confirmed_by VARCHAR(64) DEFAULT NULL",
        "confirmed_at DATETIME DEFAULT NULL",
        "confirm_remark VARCHAR(1000) DEFAULT NULL");
  }

  @Test
  @DisplayName("索引支持按确认号和产品行范围查询")
  void indexesSupportItemScopedLookup() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_quote_bom_confirm_no (confirm_no)",
        "KEY idx_quote_bom_confirm_item (oa_no, oa_form_item_id, top_product_code, period_month)",
        "KEY idx_quote_bom_confirm_status (confirm_status)");
  }

  @Test
  @DisplayName("不重建或清空成本结果表")
  void doesNotDropOrDeleteCostRunResult() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V163QuoteCostingWorkbenchBomConfirmationSqlTest.class.getResourceAsStream(
        "/db/V163__quote_costing_workbench_bom_confirmation.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V163 SQL 失败", e);
    }
  }
}
