package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V142 报价产品 BOM 准备补录层 schema")
class V142QuoteBomPreparationSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("扩展产品行 BOM 状态表的准备上下文字段")
  void extendsQuoteBomStatusWithPreparationContext() {
    assertThat(SQL).contains(
        "CALL v142_add_column_if_not_exists(\n  'lp_quote_bom_status',\n  'preparation_record_id'",
        "`product_type` VARCHAR(32) DEFAULT NULL COMMENT ''产品形态：BARE/NON_BARE/UNKNOWN''",
        "`bare_product_code` VARCHAR(64) DEFAULT NULL COMMENT ''裸品料号；裸品场景通常等于报价产品料号''",
        "`reference_finished_code` VARCHAR(64) DEFAULT NULL COMMENT ''技术员选择的参考成品料号''",
        "`source_top_product_code` VARCHAR(64) DEFAULT NULL COMMENT ''包装参考来源顶层成品料号''",
        "`review_status` VARCHAR(32) NOT NULL DEFAULT ''NOT_SUBMITTED'' COMMENT ''财务审核状态预留''",
        "idx_quote_bom_status_reference_finished");
  }

  @Test
  @DisplayName("新增准备记录、包装参考和补录 BOM 独立表")
  void createsPreparationAndSupplementTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_preparation_record",
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_package_reference",
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_package_reference_detail",
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_supplement_version",
        "CREATE TABLE IF NOT EXISTS lp_quote_bom_supplement_detail",
        "supplement_scope VARCHAR(32) NOT NULL COMMENT 'NON_BARE_FULL_BOM/BARE_BODY_BOM'");
  }

  @Test
  @DisplayName("包装参考明细同时保留快照原值和调整后值")
  void packageReferenceDetailKeepsOriginalAndAdjustedValues() {
    assertThat(SQL).contains(
        "reference_finished_code VARCHAR(64) NOT NULL COMMENT '技术员选择的参考成品料号'",
        "source_top_product_code VARCHAR(64) NOT NULL COMMENT '包装结构快照来源顶层成品料号'",
        "package_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：包装父件相对直接父件用量'",
        "adjusted_package_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：包装父件相对直接父件用量'",
        "child_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '参考快照：子件相对包装父件用量'",
        "adjusted_child_qty_per_parent DECIMAL(20,8) DEFAULT NULL COMMENT '技术员调整：子件相对包装父件用量'",
        "selected_flag TINYINT(1) NOT NULL DEFAULT 1",
        "edited_flag TINYINT(1) NOT NULL DEFAULT 0");
  }

  @Test
  @DisplayName("统一变更日志和结算行来源追溯表落地")
  void createsChangeLogAndCostingSourceRef() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_business_change_log",
        "biz_domain VARCHAR(64) NOT NULL COMMENT '业务域，如 QUOTE_BOM_PREPARATION'",
        "before_value TEXT DEFAULT NULL COMMENT '修改前值'",
        "after_value TEXT DEFAULT NULL COMMENT '修改后值'",
        "CREATE TABLE IF NOT EXISTS lp_bom_costing_row_source_ref",
        "source_part_type VARCHAR(32) NOT NULL COMMENT 'RAW_PRODUCT_BOM/BARE_PRODUCT_BOM/REFERENCED_PACKAGE/MANUAL_SUPPLEMENT'",
        "reference_finished_code VARCHAR(64) DEFAULT NULL COMMENT '参考成品料号'");
  }

  @Test
  @DisplayName("迁移幂等且不把补录写入 U9 或正式 BOM 层")
  void isIdempotentAndDoesNotMixSupplementIntoFormalBom() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS")
        .contains("v142_add_column_if_not_exists")
        .contains("v142_add_index_if_not_exists")
        .contains("DROP PROCEDURE IF EXISTS v142_add_column_if_not_exists")
        .doesNotContain("INSERT INTO lp_bom_raw_hierarchy")
        .doesNotContain("INSERT INTO lp_bom_u9_source")
        .doesNotContain("DROP TABLE lp_bom_raw_hierarchy")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_quote_bom_status");
  }

  private static String readSql() {
    try (var in = V142QuoteBomPreparationSchemaSqlTest.class.getResourceAsStream(
        "/db/V142__quote_bom_preparation_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V142 SQL 失败", e);
    }
  }
}
