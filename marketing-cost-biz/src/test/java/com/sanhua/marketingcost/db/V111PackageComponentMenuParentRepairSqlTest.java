package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V111 包装组件价格菜单层级修复")
class V111PackageComponentMenuParentRepairSqlTest {

  private static final String SQL = readSql("/db/V111__package_component_menu_parent_repair.sql");

  @Test
  @DisplayName("包装组件价格重新挂到价格源管理根菜单")
  void repairsPackageComponentParentToPriceRoot() {
    assertThat(SQL).contains(
        "UPDATE sys_menu package_menu",
        "menu_name = '价格源管理'",
        "package_menu.parent_id = price_root.menu_id",
        "package_menu.path = '/price/package-component'",
        "package_menu.component = 'price/package-component/index'");
  }

  @Test
  @DisplayName("包装组件价格页面不展示单独菜单图标")
  void clearsPackageComponentPageIcon() {
    assertThat(SQL).contains(
        "package_menu.icon = '#'",
        "package_menu.order_num = 5");
  }

  @Test
  @DisplayName("按钮权限仍挂在包装组件价格页面下")
  void keepsButtonPermissionsUnderPackageComponentMenu() {
    assertThat(SQL).contains(
        "SET parent_id = 40449",
        "WHERE perms LIKE 'price:package-component:%'",
        "AND menu_type = 'F'");
  }

  private static String readSql(String resource) {
    try (var in = V111PackageComponentMenuParentRepairSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}

