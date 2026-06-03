package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V156 价格准备按核算月隔离")
class V156PricePreparePeriodMonthScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("明细和缺口补齐 period_month 并从批次回填")
  void addsAndBackfillsPeriodMonth() {
    assertThat(SQL).contains(
        "lp_price_prepare_item",
        "lp_price_prepare_gap",
        "'period_month'",
        "JOIN lp_price_prepare_batch b ON b.prepare_no = i.prepare_no",
        "JOIN lp_price_prepare_batch b ON b.prepare_no = g.prepare_no",
        "DATE_FORMAT(CURRENT_DATE(), '%Y-%m')");
  }

  @Test
  @DisplayName("当前结果唯一键包含核算月")
  void currentKeysIncludePeriodMonth() {
    assertThat(SQL).contains(
        "DROP INDEX ', p_index_name",
        "UNIQUE KEY uk_price_prepare_item_current (oa_no, period_month, top_product_code, material_code)",
        "UNIQUE KEY uk_price_prepare_gap_current (oa_no, period_month, top_product_code, material_code, gap_material_code, gap_type, item_type)",
        "KEY idx_price_prepare_item_oa_period_top (oa_no, period_month, top_product_code)",
        "KEY idx_price_prepare_gap_oa_period_top (oa_no, period_month, top_product_code)");
  }

  @Test
  @DisplayName("迁移只清理同 OA 同月份同料号重复当前结果")
  void dedupesOnlyWithinSamePeriod() {
    assertThat(SQL)
        .contains("DELETE i1 FROM lp_price_prepare_item i1")
        .contains("AND i1.period_month = i2.period_month")
        .contains("DELETE g1 FROM lp_price_prepare_gap g1")
        .contains("AND g1.period_month = g2.period_month")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V156PricePreparePeriodMonthScopeSqlTest.class.getResourceAsStream(
        "/db/V156__price_prepare_period_month_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V156 SQL 失败", e);
    }
  }
}
