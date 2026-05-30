package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V140 报价单产品 BOM 月度沿用快照")
class V140QuoteBomMonthlySnapshotSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增组合级月度 BOM 快照表")
  void createsMonthlySnapshotTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_monthly_snapshot",
        "product_code VARCHAR(64) NOT NULL COMMENT '产品料号'",
        "customer_code VARCHAR(128) NOT NULL DEFAULT '' COMMENT '客户编码/客户名归一化值，空值统一为空串'",
        "package_method VARCHAR(128) NOT NULL DEFAULT '' COMMENT '包装方式归一化值，空值统一为空串'",
        "cost_period_month VARCHAR(7) NOT NULL COMMENT '成本核算年月，格式 YYYY-MM'",
        "sync_type VARCHAR(32) NOT NULL COMMENT '同步类型：AUTO_SYNC/MANUAL_SYNC/REUSE/MANUAL_ENTERED'",
        "sync_status VARCHAR(32) NOT NULL COMMENT '同步状态：SUCCESS/FAILED/SYNCING'",
        "active_flag TINYINT NOT NULL DEFAULT 0 COMMENT '是否当前 active 成功记录：1 是，0 否'");
  }

  @Test
  @DisplayName("月度快照表提供沿用组合键和来源追溯索引")
  void snapshotTableContainsReuseAndTraceIndexes() {
    assertThat(SQL).contains(
        "KEY idx_quote_bom_monthly_key",
        "product_code,\n    customer_code,\n    package_method,\n    cost_period_month,\n    active_flag",
        "KEY idx_quote_bom_monthly_source (source_oa_no, source_oa_form_item_id)",
        "KEY idx_quote_bom_monthly_status (sync_status, active_flag)");
  }

  @Test
  @DisplayName("产品行状态表补齐成本年月和同步/沿用追溯字段")
  void quoteBomStatusAddsTraceFieldsAndIndexes() {
    assertThat(SQL).contains(
        "CALL v140_add_column_if_not_exists(\n  'lp_quote_bom_status',\n  'cost_period_month'",
        "`cost_period_month` VARCHAR(7) DEFAULT NULL COMMENT ''成本核算年月，格式 YYYY-MM''",
        "CALL v140_add_column_if_not_exists(\n  'lp_quote_bom_status',\n  'sync_record_id'",
        "CALL v140_add_column_if_not_exists(\n  'lp_quote_bom_status',\n  'reused_from_record_id'",
        "CALL v140_add_column_if_not_exists(\n  'lp_quote_bom_status',\n  'sync_at'",
        "idx_quote_bom_status_cost_key",
        "idx_quote_bom_status_sync_record",
        "idx_quote_bom_status_reused_record");
  }

  @Test
  @DisplayName("迁移归一化历史空客户和空包装方式")
  void normalizesHistoricBlankKeyFields() {
    assertThat(SQL).contains(
        "UPDATE lp_quote_bom_status\n   SET customer_code = ''",
        "OR TRIM(customer_code) = '/'",
        "UPDATE lp_quote_bom_status\n   SET package_method = ''",
        "OR TRIM(package_method) = '/'");
  }

  @Test
  @DisplayName("迁移可重复执行且不清空历史状态数据")
  void isIdempotentAndDoesNotDeleteHistoricStatusRows() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS lp_quote_bom_monthly_snapshot")
        .contains("v140_add_column_if_not_exists")
        .contains("v140_add_index_if_not_exists")
        .contains("DROP PROCEDURE IF EXISTS v140_add_column_if_not_exists")
        .doesNotContain("DROP TABLE lp_quote_bom_status")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_quote_bom_status")
        .doesNotContain("UNIQUE KEY idx_quote_bom_monthly_key");
  }

  private static String readSql() {
    try (var in = V140QuoteBomMonthlySnapshotSqlTest.class.getResourceAsStream(
        "/db/V140__quote_bom_monthly_snapshot.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V140 SQL 失败", e);
    }
  }
}
