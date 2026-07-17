package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V193FinanceCuQuoteBaseRulesMenuSqlTest {

  @Test
  @DisplayName("V193将财务Cu报价基准移动到规则配置并桥接父目录权限")
  void rulesMenuMigrationContract() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V193__move_finance_cu_quote_base_to_rules.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "parent_id = 40475",
        "path = 'finance-cu-base'",
        "pages:FinanceCuBasePricePage",
        "cost:finance-cu-base:query",
        "WHERE menu_id = 40477",
        "INSERT IGNORE INTO sys_role_menu");
    assertThat(sql.toUpperCase()).doesNotContain("DELETE FROM SYS_MENU", "DROP TABLE");
  }
}
