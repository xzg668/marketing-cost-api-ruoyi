package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V194MetalBasePricePolicySqlTest {

  @Test
  @DisplayName("V194新增取价策略并将简化页面保留在规则配置目录")
  void metalBasePricePolicyMigrationContract() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V194__metal_base_price_policy.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "lp_quote_base_price_mapping_rule",
        "lp_factor_quote_base_mapping",
        "price_policy",
        "OA_PRIORITY",
        "FACTOR_MONTHLY",
        "menu_name = '金属基价取值规则'",
        "parent_id = 40475",
        "WHERE menu_id = 40421");
    assertThat(sql.toUpperCase()).doesNotContain("DELETE FROM SYS_MENU", "DROP TABLE");
  }
}
