package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V166 单产品核算工作台成本版本表")
class V166QuoteCostRunVersionSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("创建成本试算和确认版本表")
  void createsCostRunVersionTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_cost_run_version",
        "cost_run_no VARCHAR(64) NOT NULL",
        "version_no VARCHAR(64) DEFAULT NULL",
        "oa_form_item_id BIGINT NOT NULL",
        "price_prepare_no VARCHAR(64) DEFAULT NULL",
        "status VARCHAR(32) NOT NULL DEFAULT 'TRIAL'",
        "total_cost DECIMAL(20,6) DEFAULT NULL");
  }

  @Test
  @DisplayName("成本结果和明细表补齐版本追踪字段")
  void extendsResultAndItemTables() {
    assertThat(SQL).contains(
        "'lp_cost_run_result'",
        "'oa_form_item_id'",
        "'cost_run_version_id'",
        "'cost_run_no'",
        "'pricing_month'",
        "'price_prepare_no'",
        "'price_type_confirm_no'",
        "'result_status'",
        "'lp_cost_run_part_item'",
        "'bom_row_id'",
        "'price_prepare_item_id'",
        "'lp_cost_run_cost_item'");
  }

  @Test
  @DisplayName("唯一键和查询索引包含产品行和 cost_run_no")
  void indexesIncludeQuoteItemAndRunNo() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_quote_cost_run_no (cost_run_no)",
        "UNIQUE KEY uk_quote_cost_version_no (version_no)",
        "KEY idx_quote_cost_item_status (oa_no, oa_form_item_id, product_code, status)",
        "CALL v166_drop_index_if_exists('lp_cost_run_result', 'uk_cost_run_result')",
        "UNIQUE KEY uk_cost_run_result_quote_run (oa_no, oa_form_item_id, product_code, period, cost_run_no)",
        "KEY idx_cost_result_quote_run (oa_no, oa_form_item_id, product_code, period, cost_run_no)",
        "KEY idx_cost_part_quote_run (oa_no, oa_form_item_id, product_code, cost_run_no)",
        "KEY idx_cost_item_quote_run (oa_no, oa_form_item_id, product_code, cost_run_no)");
  }

  @Test
  @DisplayName("不重建或清空成本结果表")
  void doesNotDropOrDeleteCostRunResult() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V166QuoteCostRunVersionSqlTest.class.getResourceAsStream(
        "/db/V166__quote_cost_run_version.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V166 SQL 失败", e);
    }
  }
}
