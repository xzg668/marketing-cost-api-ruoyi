package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V131 自制件价格取价时点")
class V131MakePartPriceAsOfTimeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("自制件生成结果补充 price_as_of_time 并回填旧数据")
  void addsPriceAsOfTimeColumn() {
    assertThat(SQL)
        .contains("'lp_make_part_price_calc_row'")
        .contains("'price_as_of_time'")
        .contains("SET price_as_of_time = COALESCE(price_as_of_time, created_at, NOW())")
        .contains("MODIFY COLUMN price_as_of_time DATETIME NOT NULL");
  }

  @Test
  @DisplayName("当前唯一键扩展为期间 + 取价时点，避免月度快照互相覆盖")
  void replacesCurrentUniqueKeyWithAsOfKey() {
    assertThat(SQL)
        .contains("CALL v131_drop_index_if_exists('lp_make_part_price_calc_row', 'uk_make_part_price_current')")
        .contains("UNIQUE KEY uk_make_part_price_current_as_of (oa_no, pricing_month, price_as_of_time, parent_material_no, child_material_no, scrap_code)")
        .contains("KEY idx_make_part_price_as_of_lookup (parent_material_no, oa_no, business_unit_type, pricing_month, price_as_of_time)");
  }

  private static String readSql() {
    try (var in = V131MakePartPriceAsOfTimeSqlTest.class.getResourceAsStream(
        "/db/V131__make_part_price_as_of_time.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V131 SQL 失败", e);
    }
  }
}
