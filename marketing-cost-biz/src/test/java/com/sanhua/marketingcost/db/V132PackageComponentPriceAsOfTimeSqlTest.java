package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V132 包装组件价格取价时点")
class V132PackageComponentPriceAsOfTimeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("包装组件价格主表补充 price_as_of_time 并回填旧数据")
  void addsPriceAsOfTimeColumn() {
    assertThat(SQL)
        .contains("'lp_package_component_price'")
        .contains("'price_as_of_time'")
        .contains("SET price_as_of_time = COALESCE(price_as_of_time, generated_at, created_at, NOW())")
        .contains("MODIFY COLUMN price_as_of_time DATETIME NOT NULL");
  }

  @Test
  @DisplayName("唯一键包含取价时点，支持同月同包装件多个月度快照")
  void replacesPackageUniqueKeyWithAsOfKey() {
    assertThat(SQL)
        .contains("CALL v132_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top')")
        .contains("UNIQUE KEY uk_pkg_price_month_top_as_of (package_material_code, period_month, source_top_product_code, price_as_of_time)")
        .contains("KEY idx_pkg_price_as_of_lookup (period_month, source_top_product_code, package_material_code, price_as_of_time)");
  }

  private static String readSql() {
    try (var in = V132PackageComponentPriceAsOfTimeSqlTest.class.getResourceAsStream(
        "/db/V132__package_component_price_as_of_time.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V132 SQL 失败", e);
    }
  }
}
