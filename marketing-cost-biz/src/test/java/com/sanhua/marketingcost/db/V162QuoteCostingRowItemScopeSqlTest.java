package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V162 报价核算 BOM 行按产品行隔离")
class V162QuoteCostingRowItemScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐产品行维度和 BOM 行编辑字段")
  void addsQuoteItemAndEditableFields() {
    assertThat(SQL).contains(
        "'oa_form_item_id'",
        "BIGINT DEFAULT NULL COMMENT ''OA 产品明细行 ID'' AFTER oa_no",
        "'unit'",
        "VARCHAR(64) DEFAULT NULL COMMENT ''计量单位，报价核算 BOM 编辑字段''",
        "'material_attribute'",
        "VARCHAR(100) DEFAULT NULL COMMENT ''材料属性，报价核算 BOM 编辑字段''",
        "'manual_modified'",
        "TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''是否人工修改报价核算 BOM 行''",
        "'modified_by'",
        "'modified_at'");
  }

  @Test
  @DisplayName("定位索引和唯一键包含 oa_form_item_id")
  void indexesIncludeOaFormItemId() {
    assertThat(SQL).contains(
        "idx_bom_costing_quote_item_period",
        "INDEX idx_bom_costing_quote_item_period (oa_no, oa_form_item_id, top_product_code, period_month)",
        "CALL v162_drop_index_if_exists('lp_bom_costing_row', 'uk_oa_material_version')",
        "uk_bom_costing_item_material_version",
        "UNIQUE KEY uk_bom_costing_item_material_version (oa_no, oa_form_item_id, top_product_code, material_code, as_of_date, raw_version_effective_from)");
  }

  @Test
  @DisplayName("迁移幂等且不改写 U9 原始 BOM")
  void isIdempotentAndDoesNotTouchRawBom() {
    assertThat(SQL)
        .contains("v162_add_column_if_not_exists")
        .contains("v162_add_index_if_not_exists")
        .contains("v162_drop_index_if_exists")
        .contains("DROP PROCEDURE IF EXISTS v162_add_column_if_not_exists")
        .doesNotContain("INSERT INTO lp_bom_raw_hierarchy")
        .doesNotContain("UPDATE lp_bom_raw_hierarchy")
        .doesNotContain("DELETE FROM lp_bom_raw_hierarchy")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V162QuoteCostingRowItemScopeSqlTest.class.getResourceAsStream(
        "/db/V162__quote_costing_row_item_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V162 SQL 失败", e);
    }
  }
}
