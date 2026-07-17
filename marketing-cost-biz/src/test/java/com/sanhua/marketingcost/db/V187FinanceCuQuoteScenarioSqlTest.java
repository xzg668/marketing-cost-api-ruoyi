package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V187 财务 Cu 双场景数据契约")
class V187FinanceCuQuoteScenarioSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("价格准备批次增加场景字段并把历史数据回填为 OA_LOCKED")
  void extendsPricePrepareScenarioContract() {
    assertThat(SQL)
        .contains("'scenario_type'")
        .contains("'scenario_group_no'")
        .contains("'source_prepare_no'")
        .contains("SET scenario_type = 'OA_LOCKED'")
        .contains("DEFAULT 'OA_LOCKED'")
        .contains("idx_price_prepare_scenario")
        .contains("idx_price_prepare_scenario_group");
  }

  @Test
  @DisplayName("价格准备明细增加稳定结算键且历史行允许为空")
  void addsStableSettlementKey() {
    assertThat(SQL)
        .contains("'settlement_key'")
        .contains("VARCHAR(192) DEFAULT NULL")
        .contains("idx_price_prepare_item_settlement (prepare_no, settlement_key)");
  }

  @Test
  @DisplayName("创建场景汇总和 Cu 差额明细表及防重键")
  void createsScenarioAndDiffTables() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS lp_quote_cost_price_scenario")
        .contains("UNIQUE KEY uk_quote_cost_version_scenario (cost_run_version_id, scenario_type)")
        .contains("CREATE TABLE IF NOT EXISTS lp_quote_cu_material_diff_item")
        .contains("UNIQUE KEY uk_quote_cu_diff_line (cost_run_version_id, settlement_key)")
        .contains("parent_settlement_key")
        .contains("contributes_to_adjustment")
        .contains("trace_json JSON");
  }

  @Test
  @DisplayName("成本版本和结果表增加双场景金额快照字段")
  void extendsCostVersionAndResultSummary() {
    assertThat(SQL)
        .contains("'oa_price_prepare_no'")
        .contains("'finance_price_prepare_no'")
        .contains("'finance_cu_price'")
        .contains("'oa_cu_price'")
        .contains("'finance_base_price_id'")
        .contains("'finance_material_cost'")
        .contains("'oa_material_cost'")
        .contains("'cu_material_adjustment'")
        .contains("'final_quote_amount'");
  }

  private static String readSql() {
    try (var in = V187FinanceCuQuoteScenarioSqlTest.class.getResourceAsStream(
        "/db/V187__finance_cu_quote_scenario_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V187 SQL 失败", e);
    }
  }
}
