package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V110 价格准备当前结果唯一键")
class V110PricePrepareCurrentResultKeysSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐包装组件 OA 字段但唯一键按月份 + 成品 + 包装父料号复用")
  void packagePriceUsesOaTopPackageKey() {
    assertThat(SQL).contains(
        "'lp_package_component_price'",
        "'oa_no'",
        "CALL v110_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_month_top')",
        "CALL v110_drop_index_if_exists('lp_package_component_price', 'uk_pkg_price_oa_top')",
        "UNIQUE KEY uk_pkg_price_month_top (package_material_code, period_month, source_top_product_code)",
        "KEY idx_pkg_price_month_top (period_month, source_top_product_code)");
  }

  @Test
  @DisplayName("价格准备明细和缺口使用当前结果唯一键")
  void pricePrepareUsesCurrentKeys() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_price_prepare_item_current (oa_no, top_product_code, material_code)",
        "UNIQUE KEY uk_price_prepare_gap_current (oa_no, top_product_code, material_code, gap_material_code, gap_type, item_type)",
        "PPR-11 后主链路不依赖");
  }

  @Test
  @DisplayName("自制件最终价和缺口使用 OA + 父件 + 子件口径")
  void makePartUsesCurrentKeys() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_make_part_price_current (oa_no, parent_material_no, child_material_no)",
        "UNIQUE KEY uk_make_gap_current (oa_no, parent_material_no, child_material_no, missing_price_role, missing_material_no)");
  }

  @Test
  @DisplayName("迁移只清理重复当前结果，不删除整表")
  void migrationDoesNotDropTables() {
    assertThat(SQL)
        .contains("DELETE i1 FROM lp_price_prepare_item i1")
        .contains("DELETE p1 FROM lp_package_component_price p1")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V110PricePrepareCurrentResultKeysSqlTest.class.getResourceAsStream(
        "/db/V110__price_prepare_current_result_keys.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V110 SQL 失败", e);
    }
  }
}
