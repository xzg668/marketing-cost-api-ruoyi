package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

@Tag("integration")
class SettlementMenuRetirementIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;

  @Test void forwardMigrationRetiresOnlyIndependentBaseEntriesAndPreservesAllRoleLinksAndPrices() throws Exception {
    script("V333__settlement_price_menus.sql");
    var roles = jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id");
    var unrelated = jdbc.queryForList("SELECT * FROM sys_menu WHERE COALESCE(perms,'') NOT IN ('price:settlement-base:list','price:settlement-base:edit') ORDER BY menu_id");
    var prices = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    var historical = jdbc.queryForList("SELECT * FROM lp_settlement_base_price ORDER BY id");
    script("V339__retire_separate_settlement_base_menu.sql");
    var retired = jdbc.queryForList("SELECT * FROM sys_menu WHERE perms IN ('price:settlement-base:list','price:settlement-base:edit') ORDER BY menu_id");
    assertThat(retired).hasSize(2).allSatisfy(row -> {
      assertThat(row.get("status")).isEqualTo("1"); assertThat(row.get("visible")).isEqualTo("1");
    });
    script("V339__retire_separate_settlement_base_menu.sql");
    assertThat(jdbc.queryForList("SELECT * FROM sys_menu WHERE perms IN ('price:settlement-base:list','price:settlement-base:edit') ORDER BY menu_id")).isEqualTo(retired);
    assertThat(jdbc.queryForList("SELECT * FROM sys_menu WHERE COALESCE(perms,'') NOT IN ('price:settlement-base:list','price:settlement-base:edit') ORDER BY menu_id")).isEqualTo(unrelated);
    assertThat(jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id")).isEqualTo(roles);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(prices);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_base_price ORDER BY id")).isEqualTo(historical);
  }

  private void script(String name) throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/" + name));
    }
  }
}
