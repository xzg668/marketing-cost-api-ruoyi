package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V100 制造件价格生成菜单 seed")
class V100MakePartPriceCalcMenuSqlTest {

  private static final String SQL = readSql("/db/V100__make_part_price_calc_menu.sql");
  private static final String V96_SQL = readSql("/db/V96__u9_material_master_menu.sql");

  @Test
  @DisplayName("菜单挂在价格源管理下并使用制造件价格生成页面")
  void createsMakePartPriceCalcMenu() {
    assertThat(SQL).contains(
        "40445, '制造件价格生成'",
        "'/price/make-part-calc'",
        "'price/make-calc/index'",
        "price:make-part-calc:list",
        "path IN ('price', '/price')");
  }

  @Test
  @DisplayName("按钮权限覆盖查询、生成、导出")
  void seedsButtonPermissions() {
    assertThat(SQL).contains(
        "(40446, '制造件价格生成 查询', 40445",
        "(40447, '制造件价格生成 生成', 40445",
        "(40448, '制造件价格生成 导出', 40445",
        "price:make-part-calc:list",
        "price:make-part-calc:generate",
        "price:make-part-calc:export");
  }

  @Test
  @DisplayName("不占用 V96 U9 料品主档导出菜单 ID")
  void doesNotCollideWithU9MaterialMasterMenuIds() {
    assertThat(V96_SQL).contains("(40441, '料品主档导出'");
    assertThat(SQL)
        .doesNotContain("(40441, '制造件价格生成'")
        .doesNotContain("(40442, '制造件价格生成")
        .doesNotContain("(40443, '制造件价格生成")
        .doesNotContain("(40444, '制造件价格生成");
  }

  @Test
  @DisplayName("重复执行时先清理制造件价格生成旧残留")
  void cleansExistingMakePartCalcRowsBeforeSeed() {
    assertThat(SQL).contains(
        "DELETE FROM sys_role_menu",
        "DELETE FROM sys_menu",
        "path = '/price/make-part-calc'",
        "component = 'price/make-calc/index'",
        "perms LIKE 'price:make-part-calc:%'");
  }

  @Test
  @DisplayName("自定义角色按价格源管理路径继承授权")
  void grantsExistingPriceMenuRolesByPath() {
    assertThat(SQL).contains(
        "SELECT DISTINCT role_id, 40445",
        "WHERE path IN ('price', '/price')",
        "OR menu_name = '价格源管理'");
  }

  private static String readSql(String resource) {
    try (var in = V100MakePartPriceCalcMenuSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}
