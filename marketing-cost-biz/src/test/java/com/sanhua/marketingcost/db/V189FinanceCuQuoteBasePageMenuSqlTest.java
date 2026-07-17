package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V189FinanceCuQuoteBasePageMenuSqlTest {

  @Test
  @DisplayName("V189复用权限表注册财务Cu页面并承接V188按钮")
  void pageMenuMigrationContract() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V189__finance_cu_quote_base_page_menu.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "40477",
        "pages:FinanceCuBasePricePage",
        "cost:finance-cu-base:query",
        "WHERE menu_id IN (40475, 40476)",
        "INSERT IGNORE INTO sys_role_menu");
    assertThat(sql.toUpperCase()).doesNotContain("CREATE TABLE", "ALTER TABLE");
  }
}
