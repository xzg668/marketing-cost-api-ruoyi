package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V220 产品统一核算流水线状态迁移")
class V220ProductCostingPipelineStateSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("工作区只增加最近结构化错误摘要")
  void addsStructuredWorkspaceErrorSummary() {
    assertThat(SQL).contains(
        "'lp_quote_costing_workspace', 'last_error_step'",
        "'lp_quote_costing_workspace', 'last_error_code'",
        "'lp_quote_costing_workspace', 'last_error_message'");
  }

  @Test
  @DisplayName("成功成本版本固化完整输入指纹")
  void addsSuccessfulInputFingerprint() {
    assertThat(SQL).contains(
        "'lp_quote_cost_run_version', 'input_fingerprint'",
        "CHAR(64)");
  }

  @Test
  @DisplayName("迁移不覆盖历史成本和当前成功指针")
  void doesNotMutateHistoricalResults() {
    assertThat(SQL).doesNotContain(
        "UPDATE oa_form_item",
        "DELETE FROM",
        "TRUNCATE TABLE",
        "DROP TABLE",
        "UPDATE lp_quote_cost_run_version");
  }

  private static String readSql() {
    try (var input =
        V220ProductCostingPipelineStateSqlTest.class.getResourceAsStream(
            "/db/V220__product_costing_pipeline_state.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V220 SQL 失败", exception);
    }
  }
}
