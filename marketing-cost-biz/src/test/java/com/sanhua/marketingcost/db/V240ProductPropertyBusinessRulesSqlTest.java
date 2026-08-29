package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V240ProductPropertyBusinessRulesSqlTest {
  @Test
  void migrationCreatesRulesAndRemovesAnnualUsageStorage() throws Exception {
    String resource = "db/V240__replace_product_property_with_business_import_rules.sql";
    String sql;
    try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("CREATE TABLE IF NOT EXISTS `lp_product_property_rule`")
        .contains("`business_unit_type`, `property_year`, `product_attr`")
        .contains("DROP COLUMN `annual_usage`")
        .contains("DROP COLUMN `coefficient`")
        .contains("DROP COLUMN `parent_code`")
        .contains("'非标品', 0.050000")
        .contains("'定制品', 0.050000");
  }
}
