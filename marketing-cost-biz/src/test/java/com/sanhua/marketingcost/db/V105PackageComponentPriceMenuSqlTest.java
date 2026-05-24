package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V105 包装组件价格菜单 seed")
class V105PackageComponentPriceMenuSqlTest {

  private static final String SQL = readSql("/db/V105__package_component_price_menu.sql");

  @Test
  @DisplayName("包装组件价格挂在价格源管理下")
  void createsPackageComponentMenuUnderPriceRoot() {
    assertThat(SQL).contains(
        "40449, '包装组件价格'",
        "'/price/package-component'",
        "'price/package-component/index'",
        "price:package-component:list",
        "path IN ('price', '/price')");
  }

  @Test
  @DisplayName("包装组件价格页面不配置独立菜单图标")
  void packageComponentPageDoesNotRenderIcon() {
    assertThat(SQL)
        .contains("'price:package-component:list', '#', 'admin'")
        .doesNotContain("'price:package-component:list', 'Box'");
  }

  @Test
  @DisplayName("按钮权限覆盖查询、明细、生成、缺口")
  void seedsButtonPermissions() {
    assertThat(SQL).contains(
        "(40450, '包装组件价格 查询', 40449",
        "(40451, '包装组件价格 明细', 40449",
        "(40452, '包装组件价格 生成', 40449",
        "(40453, '包装组件缺口清单', 40449",
        "price:package-component:detail",
        "price:package-component:generate",
        "price:package-component:gaps");
  }

  private static String readSql(String resource) {
    try (var in = V105PackageComponentPriceMenuSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}

