package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V174 成本核算底稿快照表")
class V174CostRunTraceSnapshotSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("创建成本核算底稿快照表")
  void createsTraceSnapshotTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_cost_run_trace_snapshot",
        "cost_run_version_id BIGINT NOT NULL",
        "cost_run_no VARCHAR(64) NOT NULL",
        "version_no VARCHAR(64) DEFAULT NULL",
        "oa_form_item_id BIGINT NOT NULL",
        "product_code VARCHAR(64) NOT NULL",
        "pricing_month VARCHAR(7) NOT NULL",
        "trace_type VARCHAR(32) NOT NULL",
        "trace_key VARCHAR(128) NOT NULL");
  }

  @Test
  @DisplayName("部品、费用和来源定位字段齐全")
  void includesTraceReferenceColumns() {
    assertThat(SQL).contains(
        "part_item_id BIGINT DEFAULT NULL",
        "cost_item_id BIGINT DEFAULT NULL",
        "bom_row_id BIGINT DEFAULT NULL",
        "price_prepare_item_id BIGINT DEFAULT NULL",
        "material_code VARCHAR(64) DEFAULT NULL",
        "material_name VARCHAR(180) DEFAULT NULL",
        "cost_code VARCHAR(64) DEFAULT NULL",
        "cost_name VARCHAR(180) DEFAULT NULL",
        "source_type VARCHAR(64) DEFAULT NULL",
        "source_batch_no VARCHAR(128) DEFAULT NULL",
        "source_ref_id BIGINT DEFAULT NULL");
  }

  @Test
  @DisplayName("金额和 JSON 快照字段按设计存在")
  void includesAmountAndJsonSnapshotColumns() {
    assertThat(SQL).contains(
        "unit_price DECIMAL(20,8) DEFAULT NULL",
        "quantity DECIMAL(20,8) DEFAULT NULL",
        "base_amount DECIMAL(20,8) DEFAULT NULL",
        "rate DECIMAL(20,8) DEFAULT NULL",
        "amount DECIMAL(20,8) DEFAULT NULL",
        "summary VARCHAR(512) DEFAULT NULL",
        "source_snapshot_json JSON DEFAULT NULL",
        "formula_snapshot_json JSON DEFAULT NULL",
        "variables_json JSON DEFAULT NULL",
        "steps_json JSON DEFAULT NULL",
        "children_json JSON DEFAULT NULL");
  }

  @Test
  @DisplayName("唯一键和查询索引按 costRunNo 隔离")
  void indexesUseCostRunNoAsTraceBoundary() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_cost_trace_key (cost_run_no, trace_type, trace_key)",
        "KEY idx_cost_trace_version (cost_run_version_id)",
        "KEY idx_cost_trace_run (cost_run_no)",
        "KEY idx_cost_trace_part (cost_run_no, material_code)",
        "KEY idx_cost_trace_cost (cost_run_no, cost_code)",
        "KEY idx_cost_trace_prepare_item (price_prepare_item_id)");
  }

  @Test
  @DisplayName("只新增底稿表，不重建或清空既有成本版本和明细表")
  void doesNotDropOrDeleteExistingCostRunTables() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_quote_cost_run_version")
        .doesNotContain("DROP TABLE lp_cost_run_part_item")
        .doesNotContain("DROP TABLE lp_cost_run_cost_item")
        .doesNotContain("DELETE FROM lp_quote_cost_run_version")
        .doesNotContain("DELETE FROM lp_cost_run_part_item")
        .doesNotContain("DELETE FROM lp_cost_run_cost_item")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V174CostRunTraceSnapshotSqlTest.class.getResourceAsStream(
        "/db/V174__cost_run_trace_snapshot.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V174 SQL 失败", e);
    }
  }
}
