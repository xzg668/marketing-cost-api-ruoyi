package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V85 报价单基价映射 DDL")
class V85QuoteBasePriceMappingSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增规则表和识别结果表")
  void createsQuoteBaseMappingTables() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_quote_base_price_mapping_rule",
        "CREATE TABLE IF NOT EXISTS lp_factor_quote_base_mapping",
        "quote_field_code",
        "quote_field_name",
        "variable_code",
        "match_keywords_json",
        "factor_identity_id");
  }

  @Test
  @DisplayName("唯一键按规则和影响因素识别结果去重")
  void definesUniqueKeysForRuleAndMapping() {
    assertThat(SQL).contains(
        "uk_quote_base_mapping_rule",
        "business_unit_type, quote_field_code, variable_code, deleted",
        "uk_factor_quote_base_mapping",
        "factor_identity_id, quote_field_code, deleted");
  }

  @Test
  @DisplayName("保留后续计算查询需要的索引")
  void keepsLookupIndexesForCalculation() {
    assertThat(SQL).contains(
        "idx_quote_base_rule_field",
        "idx_quote_base_rule_bu_priority",
        "idx_factor_quote_base_field",
        "idx_factor_quote_base_rule",
        "idx_factor_quote_base_source");
  }

  @Test
  @DisplayName("字段语义说明覆盖 OA 表头基价和自动/人工识别")
  void documentsQuoteFieldAndMatchSourceSemantics() {
    assertThat(SQL).contains(
        "OA 报价单基价字段编码",
        "AUTO=规则自动识别，MANUAL=人工调整",
        "空串表示全业务单元默认规则",
        "当前阶段只承接 Cu/Zn/Al");
  }

  @Test
  @DisplayName("使用 IF NOT EXISTS，避免破坏已有库")
  void isIdempotentAndNonDestructive() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V85QuoteBasePriceMappingSchemaSqlTest.class.getResourceAsStream(
        "/db/V85__quote_base_price_mapping_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V85 SQL 失败", e);
    }
  }
}
