package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V151 联动公式版本唯一键")
class V151PriceLinkedItemFormulaVersionUniqueKeySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("删除阻止公式多版本的旧唯一键")
  void dropsOldSingleVersionUniqueKey() {
    assertThat(SQL)
        .contains("uk_linked_month_mat_supp_bu")
        .contains("DROP INDEX");
  }

  @Test
  @DisplayName("新增自然键加 effective_from 的版本唯一键")
  void addsFormulaVersionUniqueKey() {
    assertThat(SQL)
        .contains("uk_linked_formula_version")
        .contains("`pricing_month`, `material_code`, `supplier_code`, `spec_model`,")
        .contains("`business_unit_type`, `effective_from`");
  }

  @Test
  @DisplayName("新增当前版本查询索引")
  void addsCurrentVersionLookupIndex() {
    assertThat(SQL)
        .contains("idx_linked_current_version_lookup")
        .contains("`business_unit_type`, `effective_to`, `deleted`");
  }

  private static String readSql() {
    try (var in = V151PriceLinkedItemFormulaVersionUniqueKeySqlTest.class.getResourceAsStream(
        "/db/V151__price_linked_item_formula_version_unique_key.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V151 SQL 失败", e);
    }
  }
}
