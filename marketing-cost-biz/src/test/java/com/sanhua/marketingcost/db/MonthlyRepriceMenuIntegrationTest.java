package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Actual deployed menu identities plus an obsolete duplicate, on disposable MySQL only. */
@Tag("integration")
class MonthlyRepriceMenuIntegrationTest extends OriginalPricingIntegrationTestBase {
  @Autowired JdbcTemplate jdbc;
  @Test void migrationMovesOnlyTheCanonicalEntryAndPreservesAllExistingGrantsAndRows() {
    jdbc.update("INSERT IGNORE INTO sys_menu(menu_id,menu_name,parent_id,order_num,path,component,menu_type,status,visible) VALUES(40161,'核算与结账',0,5,'cost',NULL,'M','1','1'),(40163,'结账',0,6,'settlement',NULL,'M','0','1'),(40197,'月度调价',40161,1,'/settlement/monthly-adjustment','settlement/monthly-adjustment/index','C','1','1')");
    new ResourceDatabasePopulator(new ClassPathResource("db/V128__monthly_reprice_menu_seed.sql")).execute(jdbc.getDataSource());
    jdbc.update("INSERT IGNORE INTO sys_role_menu(role_id,menu_id) VALUES(10,40197),(11,40197)");
    int before=jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu",Integer.class);
    var old=jdbc.queryForMap("SELECT * FROM sys_menu WHERE menu_id=200");
    var settlement=jdbc.queryForMap("SELECT * FROM sys_menu WHERE menu_id=40163");
    var grants=jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id");
    apply();apply();
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_menu",Integer.class)).isEqualTo(before);
    assertThat(jdbc.queryForMap("SELECT * FROM sys_menu WHERE menu_id=200")).isEqualTo(old);
    assertThat(jdbc.queryForMap("SELECT * FROM sys_menu WHERE menu_id=40163")).isEqualTo(settlement);
    assertThat(jdbc.queryForList("SELECT role_id,menu_id FROM sys_role_menu ORDER BY role_id,menu_id")).containsAll(grants);
    assertThat(jdbc.queryForObject("SELECT parent_id FROM sys_menu WHERE menu_id=40197",Long.class)).isEqualTo(40161L);
    assertThat(jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE menu_id=40161",String.class)).isEqualTo("核算与调价");
    assertThat(jdbc.queryForObject("SELECT menu_name FROM sys_menu WHERE menu_id=40197",String.class)).isEqualTo("月度调价");
    assertThat(jdbc.queryForMap("SELECT status,visible FROM sys_menu WHERE menu_id=40161")).containsEntry("status","0").containsEntry("visible","0");
    assertThat(jdbc.queryForMap("SELECT status,visible FROM sys_menu WHERE menu_id=40197")).containsEntry("status","0").containsEntry("visible","0");
    assertThat(jdbc.queryForObject("SELECT component FROM sys_menu WHERE menu_id=40197",String.class)).isEqualTo("pages:MonthlyRepricePage");
    assertThat(jdbc.queryForObject("SELECT perms FROM sys_menu WHERE menu_id=40197",String.class)).isEqualTo("price:monthly-reprice:list");
    assertThat(jdbc.queryForObject("SELECT path FROM sys_menu WHERE menu_id=40197",String.class)).isEqualTo("/cost/monthly-reprice");
    assertThat(jdbc.queryForObject("SELECT status FROM sys_menu WHERE menu_id=503",String.class)).isEqualTo("1");
    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_role_menu WHERE role_id IN (10,11) AND menu_id=40161",Integer.class)).isEqualTo(2);
  }
  private void apply(){new ResourceDatabasePopulator(new ClassPathResource("db/V351__monthly_reprice_1130_menu.sql")).execute(jdbc.getDataSource());}
}
