package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V109 价格准备菜单 seed")
class V109PricePrepareMenuSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("菜单挂在成本核算下并排在实时成本计算前")
  void createsPricePrepareMenuBeforeCostRun() {
    assertThat(SQL).contains(
        "40454, '价格准备'",
        "'/cost/price-prepare'",
        "'cost/price-prepare/index'",
        "'cost:price-prepare:list'",
        "WHEN c.path IN ('/cost/run', 'run') OR c.menu_name = '实时成本计算' THEN 2",
        "WHEN c.path IN ('/cost/run/completed', 'run/completed') OR c.menu_name = '已核算成本明细' THEN 3",
        "path IN ('cost', '/cost')",
        "OR menu_name = '成本核算'");
  }

  @Test
  @DisplayName("按钮权限覆盖查询、查看全部、生成、生成全部、明细和缺口")
  void seedsButtonPermissions() {
    assertThat(SQL).contains(
        "(40455, '价格准备 查询', 40454",
        "(40456, '价格准备 生成', 40454",
        "(40457, '价格准备 明细', 40454",
        "(40458, '价格准备 缺口', 40454",
        "(40459, '价格准备 查看全部', 40454",
        "(40460, '价格准备 生成全部', 40454",
        "cost:price-prepare:list",
        "cost:price-prepare:generate",
        "cost:price-prepare:detail",
        "cost:price-prepare:gap",
        "cost:price-prepare:list-all",
        "cost:price-prepare:generate-all");
  }

  @Test
  @DisplayName("重复执行时先清理旧价格准备菜单残留")
  void cleansExistingPricePrepareRowsBeforeSeed() {
    assertThat(SQL).contains(
        "DELETE FROM sys_role_menu",
        "DELETE FROM sys_menu",
        "path = '/cost/price-prepare'",
        "component = 'cost/price-prepare/index'",
        "perms LIKE 'cost:price-prepare:%'");
  }

  @Test
  @DisplayName("自定义角色按成本核算路径继承授权")
  void grantsExistingCostMenuRolesByPath() {
    assertThat(SQL).contains(
        "SELECT DISTINCT role_id, 40454",
        "WHERE path IN ('cost', '/cost')",
        "OR menu_name = '成本核算'");
  }

  private static String readSql() {
    try (var in = V109PricePrepareMenuSqlTest.class.getResourceAsStream(
        "/db/V109__price_prepare_menu.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V109 SQL 失败", e);
    }
  }
}
