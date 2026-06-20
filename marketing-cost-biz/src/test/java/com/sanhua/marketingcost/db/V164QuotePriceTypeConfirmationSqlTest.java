package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V164 单产品核算工作台价格类型确认表")
class V164QuotePriceTypeConfirmationSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("创建价格类型确认批次和明细表")
  void createsPriceTypeConfirmationTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_price_type_confirm_batch",
        "CREATE TABLE IF NOT EXISTS lp_quote_price_type_confirm_item",
        "confirm_no VARCHAR(64) NOT NULL",
        "oa_form_item_id BIGINT NOT NULL",
        "product_code VARCHAR(64) NOT NULL",
        "period_month VARCHAR(7) NOT NULL");
  }

  @Test
  @DisplayName("明细表包含取价对象、价格类型来源和状态字段")
  void itemContainsObjectTypeSourceAndStatus() {
    assertThat(SQL).contains(
        "object_type VARCHAR(32) NOT NULL",
        "price_type_source VARCHAR(32) DEFAULT NULL",
        "status VARCHAR(32) NOT NULL DEFAULT 'MISSING_TYPE'",
        "MATERIAL_PRICE_TYPE/SYNTHETIC/MANUAL",
        "NORMAL/MAKE_PARENT/MAKE_RAW/MAKE_SCRAP/PACKAGE_PARENT/PACKAGE_CHILD");
  }

  @Test
  @DisplayName("索引支持批次、产品行、状态、料号和 BOM 行查询")
  void indexesSupportWorkbenchLookup() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_quote_price_type_confirm_no (confirm_no)",
        "KEY idx_qptc_item_scope (oa_no, oa_form_item_id, product_code, period_month)",
        "KEY idx_qptc_status (status)",
        "KEY idx_qptci_confirm (confirm_no)",
        "KEY idx_qptci_material (material_code)",
        "KEY idx_qptci_bom_row (bom_row_id)");
  }

  @Test
  @DisplayName("不重建或清空成本结果表")
  void doesNotDropOrDeleteCostRunResult() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V164QuotePriceTypeConfirmationSqlTest.class.getResourceAsStream(
        "/db/V164__quote_price_type_confirmation.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V164 SQL 失败", e);
    }
  }
}
