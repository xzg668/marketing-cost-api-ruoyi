package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QEB-13 物料形态规则菜单 SQL")
class V203MaterialQuoteShapePolicyMenuSqlTest {

  @Test
  @DisplayName("菜单挂在规则配置下并指向动态页面")
  void createsPolicyMenuUnderRules() throws Exception {
    String sql = readSql();

    assertThat(sql).contains(
        "(40483, '报价 BOM 物料形态规则', 40475, 15",
        "'/rules/material-quote-shape-policy'",
        "'pages:MaterialQuoteShapePolicyPage'",
        "'bom-data:material-shape-policy:list'");
  }

  @Test
  @DisplayName("查看编辑启停权限分离且菜单迁移幂等")
  void separatesPermissionsAndIsIdempotent() throws Exception {
    String sql = readSql();

    assertThat(sql).contains(
        "'bom-data:material-shape-policy:list'",
        "'bom-data:material-shape-policy:edit'",
        "'bom-data:material-shape-policy:toggle'",
        "ON DUPLICATE KEY UPDATE",
        "INSERT IGNORE INTO sys_role_menu",
        "(1, 40475), (1, 40483), (1, 40484), (1, 40485), (1, 40486)");
    assertThat(sql).doesNotContain(
        "DELETE FROM sys_menu",
        "DELETE FROM sys_role_menu",
        "TRUNCATE TABLE");
  }

  @Test
  @DisplayName("菜单迁移不写规则、最终树和历史报价业务表")
  void doesNotMutateBusinessData() throws Exception {
    String upper = readSql().toUpperCase();

    assertThat(upper).doesNotContain(
        "INSERT INTO LP_MATERIAL_QUOTE_SHAPE_POLICY",
        "UPDATE LP_MATERIAL_QUOTE_SHAPE_POLICY",
        "DELETE FROM LP_MATERIAL_QUOTE_SHAPE_POLICY",
        "LP_QUOTE_EFFECTIVE_BOM_NODE",
        "LP_QUOTE_BOM_MONTHLY_SNAPSHOT");
  }

  private static String readSql() throws Exception {
    try (InputStream input = V203MaterialQuoteShapePolicyMenuSqlTest.class
        .getResourceAsStream("/db/V203__material_quote_shape_policy_menu.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
