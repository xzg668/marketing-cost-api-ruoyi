package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V188FinanceCuQuoteBasePermissionSqlTest {

  @Test
  @DisplayName("V188只复用系统权限表并注册FCQ-02查询/编辑权限")
  void permissionMigrationReusesExistingTables() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V188__finance_cu_quote_base_permissions.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "cost:finance-cu-base:query",
        "cost:finance-cu-base:edit",
        "INSERT INTO sys_menu",
        "INSERT IGNORE INTO sys_role_menu");
    assertThat(sql.toUpperCase()).doesNotContain("CREATE TABLE", "ALTER TABLE");
    assertThat(sql).contains("(1, 40475)", "(1, 40476)");
  }
}
