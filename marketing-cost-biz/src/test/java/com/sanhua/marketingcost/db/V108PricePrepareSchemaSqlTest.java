package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V108 价格准备表结构")
class V108PricePrepareSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增价格准备批次、明细和缺口三张表")
  void createsPricePrepareTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_price_prepare_batch",
        "CREATE TABLE IF NOT EXISTS lp_price_prepare_item",
        "CREATE TABLE IF NOT EXISTS lp_price_prepare_gap");
  }

  @Test
  @DisplayName("批次表记录 OA、期间、状态和统计数")
  void batchContainsRunSummaryColumns() {
    assertThat(SQL).contains(
        "prepare_no VARCHAR(64) NOT NULL COMMENT '价格准备批次号'",
        "oa_no VARCHAR(64) NOT NULL COMMENT 'OA单号'",
        "period_month VARCHAR(7) NOT NULL COMMENT '价格期间 YYYY-MM'",
        "bom_purpose VARCHAR(64) NOT NULL DEFAULT '主制造'",
        "source_type VARCHAR(32) NOT NULL DEFAULT 'U9'",
        "status VARCHAR(32) NOT NULL DEFAULT 'RUNNING'",
        "gap_count INT NOT NULL DEFAULT 0");
  }

  @Test
  @DisplayName("明细表保留 BOM 行、顶层产品、料号类型和结果引用")
  void itemContainsBomAndResultContext() {
    assertThat(SQL).contains(
        "top_product_code VARCHAR(64) NOT NULL COMMENT '顶级成品料号'",
        "bom_row_id BIGINT DEFAULT NULL COMMENT 'BOM结算行ID lp_bom_costing_row.id'",
        "item_type VARCHAR(32) NOT NULL COMMENT '料号类型：NORMAL/PACKAGE_COMPONENT/MAKE_PART'",
        "result_ref_type VARCHAR(64) DEFAULT NULL COMMENT '结果来源类型：PACKAGE_PRICE/MAKE_PART_PRICE/FIXED_PRICE/RANGE_PRICE/LINKED_PRICE等'",
        "UNIQUE KEY uk_price_prepare_item_row");
  }

  @Test
  @DisplayName("缺口表预留 OA 推送状态但不触发推送")
  void gapContainsOaPushStatusWithoutDestructiveSql() {
    assertThat(SQL)
        .contains(
            "gap_material_code VARCHAR(64) DEFAULT NULL COMMENT '真正缺数据的料号，包装组件时可能是子件'",
            "gap_type VARCHAR(32) NOT NULL COMMENT '缺口类型：MISSING_STRUCTURE/MISSING_PRICE_TYPE/MISSING_PRICE/MISSING_MASTER'",
            "oa_push_status VARCHAR(32) NOT NULL DEFAULT 'PENDING'",
            "当前阶段只沉淀缺价、缺结构、缺主档等缺口，不触发 OA 推送")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V108PricePrepareSchemaSqlTest.class.getResourceAsStream(
        "/db/V108__price_prepare_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V108 SQL 失败", e);
    }
  }
}
