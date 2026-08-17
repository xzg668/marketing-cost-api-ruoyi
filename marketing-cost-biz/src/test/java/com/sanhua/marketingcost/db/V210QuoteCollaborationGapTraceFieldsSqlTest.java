package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-13 协作缺口业务追溯字段 SQL")
class V210QuoteCollaborationGapTraceFieldsSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("只扩展本期新建的协作缺口表")
  void onlyExtendsCollaborationGapTable() {
    assertThat(SQL).containsOnlyOnce("ALTER TABLE `lp_quote_collaboration_gap`");
    assertThat(SQL).doesNotContain("oa_form", "lp_bom_u9_source", "lp_bom_raw_hierarchy",
        "lp_bom_costing_row", "lp_price_fixed_item");
    assertThat(SQL).contains(
        "`bom_quantity` DECIMAL(20,8) NULL",
        "`bom_unit` VARCHAR(32) NULL",
        "`accounting_month` CHAR(7) NULL",
        "`applicable_org_code` VARCHAR(64) NULL",
        "`idx_collaboration_gap_scope`");
  }

  private static String readSql() {
    try (var input = V210QuoteCollaborationGapTraceFieldsSqlTest.class.getResourceAsStream(
        "/db/V210__quote_collaboration_gap_trace_fields.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V210 SQL 失败", exception);
    }
  }
}
