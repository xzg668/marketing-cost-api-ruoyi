package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V165 价格准备按报价产品行隔离")
class V165PricePrepareQuoteItemScopeSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("批次、明细和缺口补齐产品行和价格类型确认字段")
  void addsQuoteItemAndConfirmationColumns() {
    assertThat(SQL).contains(
        "'lp_price_prepare_batch'",
        "'oa_form_item_id'",
        "'top_product_code'",
        "'price_type_confirm_no'",
        "'lp_price_prepare_item'",
        "'price_type_confirm_item_id'",
        "'lp_price_prepare_gap'",
        "'action_type'",
        "'action_target'");
  }

  @Test
  @DisplayName("产品行范围和确认批次索引按文档命名")
  void indexesFollowWorkbenchNaming() {
    assertThat(SQL).contains(
        "KEY idx_pp_batch_item_scope (oa_no, oa_form_item_id, top_product_code, period_month)",
        "KEY idx_pp_item_item_scope (oa_no, oa_form_item_id, top_product_code)",
        "KEY idx_pp_gap_item_scope (oa_no, oa_form_item_id, top_product_code)",
        "KEY idx_pp_item_confirm (price_type_confirm_no)",
        "KEY idx_pp_gap_confirm (price_type_confirm_no)");
  }

  @Test
  @DisplayName("旧唯一键使用 drop-if-exists helper 调整为产品行维度")
  void adjustsLegacyUniqueKeysWithHelper() {
    assertThat(SQL)
        .contains("v165_drop_index_if_exists")
        .contains("CALL v165_drop_index_if_exists('lp_price_prepare_item', 'uk_price_prepare_item_row')")
        .contains("UNIQUE KEY uk_price_prepare_item_row (prepare_no, oa_form_item_id, bom_row_id, material_code)")
        .contains("UNIQUE KEY uk_price_prepare_item_current (oa_no, oa_form_item_id, period_month, top_product_code, material_code)")
        .contains("UNIQUE KEY uk_price_prepare_gap_current (oa_no, oa_form_item_id, period_month, top_product_code, material_code, gap_material_code, gap_type, item_type)")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  @Test
  @DisplayName("不重建或清空成本结果表")
  void doesNotDropOrDeleteCostRunResult() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_cost_run_result")
        .doesNotContain("DELETE FROM lp_cost_run_result");
  }

  private static String readSql() {
    try (var in = V165PricePrepareQuoteItemScopeSqlTest.class.getResourceAsStream(
        "/db/V165__price_prepare_quote_item_scope.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V165 SQL 失败", e);
    }
  }
}
