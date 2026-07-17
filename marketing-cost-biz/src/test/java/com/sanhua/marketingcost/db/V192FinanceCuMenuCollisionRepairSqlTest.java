package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V192FinanceCuMenuCollisionRepairSqlTest {

  @Test
  @DisplayName("V192使用新权限ID并恢复规则配置与核算基础配置菜单")
  void menuCollisionRepairContract() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V192__repair_finance_cu_menu_id_collision.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "40478, '财务Cu基准查询'",
        "40479, '财务Cu基准维护'",
        "cost:finance-cu-base:query",
        "cost:finance-cu-base:edit",
        "menu_name = '规则配置'",
        "menu_name = '核算基础配置'",
        "WHERE menu_id IN (40171, 40239, 40187, 40421)",
        "WHERE menu_id IN (40180, 40174, 40177, 40178, 40179, 40181, 40427)");
    assertThat(sql).contains("parent_id = 0", "parent_id = 40159");
    assertThat(sql.toUpperCase()).doesNotContain("DROP TABLE", "DELETE FROM SYS_MENU");
  }
}
