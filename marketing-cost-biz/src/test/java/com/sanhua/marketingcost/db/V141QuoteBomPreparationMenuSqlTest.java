package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V141 报价产品 BOM 准备菜单重构")
class V141QuoteBomPreparationMenuSqlTest {

  private static final String SQL = readSql("/db/V141__quote_bom_preparation_menu_restructure.sql");

  @Test
  @DisplayName("报价产品 BOM 准备保留原菜单 ID 和权限")
  void keepsQuoteBomPreparationEntryStable() {
    assertThat(SQL).contains(
        "WHERE menu_id = 208",
        "menu_name = '报价产品 BOM 准备'",
        "path = 'quote-request-products/bom'",
        "component = 'ingest/quote-request-products/bom/index'",
        "perms = 'ingest:quote-product-bom:list'");
  }

  @Test
  @DisplayName("BOM 数据管理和 BOM 明细目录按目标层级落地")
  void createsBomDataManagementTree() {
    assertThat(SQL).contains(
        "40461, 'BOM 数据管理', 0",
        "40462, 'BOM 明细', 40461",
        "menu_name = 'U9 BOM 原始数据'",
        "parent_id = 40462",
        "40463, 'BOM 层级树查看', 40462",
        "40464, '包装组件结构', 40462",
        "menu_name = 'BOM 结算明细'");
  }

  @Test
  @DisplayName("BOM 规则菜单迁出基础数据但保留历史菜单 ID")
  void movesFilterRuleMenuWithoutDeletingIt() {
    assertThat(SQL).contains(
        "WHERE menu_id = 301",
        "parent_id = 40461");
  }

  @Test
  @DisplayName("补录任务和版本入口先稳定路由")
  void createsSupplementMenus() {
    assertThat(SQL).contains(
        "40467, 'BOM 补录任务', 40461",
        "'pages:BomPreparationPlaceholderPage'",
        "bom-data:supplement-task:list",
        "40468, '补录 BOM 版本', 40461",
        "bom-data:supplement-version:list");
  }

  @Test
  @DisplayName("迁移不删除菜单和角色授权")
  void doesNotDeleteMenusOrRoleGrants() {
    assertThat(SQL)
        .doesNotContain("DELETE FROM sys_menu")
        .doesNotContain("DELETE FROM sys_role_menu")
        .doesNotContain("TRUNCATE TABLE")
        .contains("INSERT IGNORE INTO sys_role_menu")
        .contains("SELECT DISTINCT role_id, 40461")
        .contains("SELECT DISTINCT role_id, 40462");
  }

  private static String readSql(String resource) {
    try (var in = V141QuoteBomPreparationMenuSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}
