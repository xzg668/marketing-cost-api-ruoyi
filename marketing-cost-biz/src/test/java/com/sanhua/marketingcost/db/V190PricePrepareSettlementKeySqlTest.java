package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V190 价格准备稳定结算键唯一性")
class V190PricePrepareSettlementKeySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("移除按料号防重的旧索引并改为批次内稳定键防重")
  void replacesMaterialUniquenessWithSettlementKeyUniqueness() {
    assertThat(SQL)
        .contains("v190_drop_index_if_exists(")
        .contains("'uk_price_prepare_item_batch'")
        .contains("'idx_price_prepare_item_settlement'")
        .contains("UNIQUE KEY uk_price_prepare_item_settlement (prepare_no, settlement_key)")
        .doesNotContain("UPDATE lp_price_prepare_item")
        .doesNotContain("DELETE FROM lp_price_prepare_item");
  }

  private static String readSql() {
    try (var in = V190PricePrepareSettlementKeySqlTest.class.getResourceAsStream(
        "/db/V190__price_prepare_settlement_key_uniqueness.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V190 SQL 失败", e);
    }
  }
}
