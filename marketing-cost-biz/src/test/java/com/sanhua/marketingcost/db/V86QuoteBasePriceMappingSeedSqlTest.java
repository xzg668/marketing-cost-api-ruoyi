package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V86 报价单基价映射基础规则 seed")
class V86QuoteBasePriceMappingSeedSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("预置 Cu/Zn/Al 三条公共基价规则")
  void seedsCopperZincAndAluminumRules() {
    assertThat(SQL).contains(
        "INSERT IGNORE INTO lp_quote_base_price_mapping_rule",
        "'copper_price'",
        "'铜基价'",
        "'Cu'",
        "'zinc_price'",
        "'锌基价'",
        "'Zn'",
        "'aluminum_price'",
        "'铝基价'",
        "'Al'");
  }

  @Test
  @DisplayName("暂不预置 OA 表头不存在或口径未确认的字段")
  void doesNotSeedUnsupportedQuoteFields() {
    assertThat(SQL)
        .doesNotContain("'tin_price'")
        .doesNotContain("'Sn'")
        .doesNotContain("'steel_price'")
        .doesNotContain("'silver_price'")
        .doesNotContain("'gold_price'")
        .doesNotContain("'sus304_price'")
        .doesNotContain("'sus316l_price'")
        .doesNotContain("'other_material'");
  }

  @Test
  @DisplayName("使用 INSERT IGNORE 保证重复执行不重复插入")
  void usesInsertIgnoreForIdempotency() {
    assertThat(SQL)
        .contains("INSERT IGNORE INTO")
        .doesNotContain("INSERT INTO lp_quote_base_price_mapping_rule");
  }

  @Test
  @DisplayName("关键词避免过宽匹配")
  void avoidsOverBroadCopperKeyword() {
    assertThat(SQL)
        .contains("电解铜", "1#铜", "A00铝", "AOO铝", "锌锭")
        .doesNotContain("\"铜\"");
  }

  private static String readSql() {
    try (var in = V86QuoteBasePriceMappingSeedSqlTest.class.getResourceAsStream(
        "/db/V86__quote_base_price_mapping_seed.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V86 SQL 失败", e);
    }
  }
}
