package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V167 成本核算工作台确认版本引用")
class V167QuoteCostRunWorkbenchConfirmedVersionSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("产品行补齐确认成本版本字段和索引")
  void addsConfirmedCostVersionReference() {
    assertThat(SQL)
        .contains(
            "'oa_form_item'",
            "'confirmed_cost_version_id'",
            "BIGINT DEFAULT NULL COMMENT ''当前确认成本版本ID'' AFTER calc_at",
            "KEY idx_oa_form_item_confirmed_cost_version (confirmed_cost_version_id)");
  }

  @Test
  @DisplayName("不重建或清空成本结果表")
  void doesNotDropOrDeleteCostRunResult() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_cost_run_result");
  }

  private static String readSql() {
    try (var in =
        V167QuoteCostRunWorkbenchConfirmedVersionSqlTest.class.getResourceAsStream(
            "/db/V167__quote_cost_run_workbench_confirmed_version.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V167 SQL 失败", e);
    }
  }
}
