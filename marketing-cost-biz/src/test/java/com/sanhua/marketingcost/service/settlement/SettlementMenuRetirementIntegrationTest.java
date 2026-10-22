package com.sanhua.marketingcost.service.settlement;

import static org.assertj.core.api.Assertions.*;

import com.sanhua.marketingcost.db.OriginalPricingIntegrationTestBase;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@Tag("integration")
class SettlementMenuRetirementIntegrationTest extends OriginalPricingIntegrationTestBase {
  private static final String RETIRED = "('price:settlement-base:list','price:settlement-base:edit','price:settlement:edit')";
  @Autowired JdbcTemplate jdbc;
  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping routes;

  @Test void freshInstallHasOnlyListAndImportWhileUpgradeRetiresOldEntriesWithoutDeletingHistory() throws Exception {
    // 清理隔离数据库的旧菜单夹具，先验证全新安装。
    jdbc.update("DELETE rm FROM sys_role_menu rm JOIN sys_menu m ON m.menu_id=rm.menu_id WHERE m.perms LIKE 'price:settlement:%' OR m.perms LIKE 'price:settlement-base:%'");
    jdbc.update("DELETE FROM sys_menu WHERE perms LIKE 'price:settlement:%' OR perms LIKE 'price:settlement-base:%'");
    script("V333__settlement_price_menus.sql");
    script("V333__settlement_price_menus.sql");
    assertThat(jdbc.queryForList("SELECT perms FROM sys_menu WHERE perms LIKE 'price:settlement:%' OR perms LIKE 'price:settlement-base:%'", String.class))
        .containsExactlyInAnyOrder("price:settlement:list", "price:settlement:import");
    // 模拟已安装旧版的菜单和角色关系，升级只能停用它们。
    for (String permission : new String[]{"price:settlement-base:list", "price:settlement-base:edit", "price:settlement:edit"}) {
      jdbc.update("INSERT INTO sys_menu(menu_name,parent_id,order_num,path,menu_type,visible,status,perms,create_by,create_time) VALUES('旧结算入口',0,99,'legacy-settlement','F','0','0',?,'fixture',NOW())", permission);
    }
    jdbc.update("INSERT INTO sys_role_menu(role_id,menu_id) SELECT r.role_id,m.menu_id FROM sys_role r JOIN sys_menu m ON m.perms IN " + RETIRED + " WHERE r.role_key='admin'");
    var roles = jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id");
    var unrelated = jdbc.queryForList("SELECT * FROM sys_menu WHERE COALESCE(perms,'') NOT IN " + RETIRED + " ORDER BY menu_id");
    var prices = jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id");
    var historical = jdbc.queryForList("SELECT * FROM lp_settlement_base_price ORDER BY id");
    retire();
    var retired = jdbc.queryForList("SELECT * FROM sys_menu WHERE perms IN " + RETIRED + " ORDER BY menu_id");
    assertThat(retired).hasSize(3).allSatisfy(row -> {
      assertThat(row.get("status")).isEqualTo("1"); assertThat(row.get("visible")).isEqualTo("1");
    });
    retire();
    script("V333__settlement_price_menus.sql");
    assertThat(jdbc.queryForList("SELECT * FROM sys_menu WHERE perms IN " + RETIRED + " ORDER BY menu_id")).isEqualTo(retired);
    assertThat(jdbc.queryForList("SELECT * FROM sys_menu WHERE COALESCE(perms,'') NOT IN " + RETIRED + " ORDER BY menu_id")).isEqualTo(unrelated);
    assertThat(jdbc.queryForList("SELECT * FROM sys_role_menu ORDER BY role_id,menu_id")).isEqualTo(roles);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_price ORDER BY id")).isEqualTo(prices);
    assertThat(jdbc.queryForList("SELECT * FROM lp_settlement_base_price ORDER BY id")).isEqualTo(historical);
  }

  @Test void applicationRegistersImportAndReadRoutesButNoSeparateBaseOrWeightMaintenanceRoutes() {
    var paths = routes.getHandlerMethods().keySet().stream().flatMap(route -> route.getPatternValues().stream()).toList();
    assertThat(paths).contains("/api/v1/settlement-prices/pricing", "/api/v1/settlement-prices/import", "/api/v1/settlement-prices/{id}/history");
    assertThat(paths).noneMatch(path -> path.startsWith("/api/v1/settlement-base-prices")
        || path.matches("/api/v1/settlement-prices/[^/]+/weights"));
  }

  private void retire() throws Exception {
    script("V339__retire_separate_settlement_base_menu.sql");
    script("V340__retire_settlement_weight_edit.sql");
  }

  private void script(String name) throws Exception {
    try (var connection = jdbc.getDataSource().getConnection()) {
      ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/" + name));
    }
  }
}
