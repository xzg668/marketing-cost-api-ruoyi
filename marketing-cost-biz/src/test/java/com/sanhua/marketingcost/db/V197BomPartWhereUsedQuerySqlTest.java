package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V197 物料使用查询")
class V197BomPartWhereUsedQuerySqlTest {

  @Test
  @DisplayName("正式表具备按组织和物料反查所需索引")
  void createsIndexedWhereUsedTable() throws Exception {
    String sql = readSql();

    assertThat(sql).contains(
        "CREATE TABLE IF NOT EXISTS lp_bom_part_where_used",
        "UNIQUE KEY uk_bom_part_where_used_relation (relation_key)",
        "KEY idx_bom_where_used_part (price_org_code, part_code)",
        "KEY idx_bom_where_used_product (price_org_code, top_product_code)");
    assertThat(sql).doesNotContain("TRUNCATE TABLE", "DELETE FROM lp_bom_part_where_used");
  }

  @Test
  @DisplayName("菜单挂在U9数据下并自动继承父目录角色")
  void createsMaterialUsageMenu() throws Exception {
    String sql = readSql();

    assertThat(sql).contains(
        "(40480, '物料使用查询', 40435",
        "'/base/u9/material-usage'",
        "'pages:MaterialUsageQueryPage'",
        "'base:u9-material-usage:list'",
        "WHERE menu_id = 40435",
        "WHERE menu_id = 40480");
    assertThat(sql).doesNotContain("DELETE FROM sys_menu", "DELETE FROM sys_role_menu");
  }

  private static String readSql() throws Exception {
    try (InputStream input = V197BomPartWhereUsedQuerySqlTest.class.getResourceAsStream(
        "/db/V197__bom_part_where_used_query.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
