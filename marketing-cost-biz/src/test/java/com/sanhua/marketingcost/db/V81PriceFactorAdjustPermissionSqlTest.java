package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V81 月度调价权限和菜单文案 seed")
class V81PriceFactorAdjustPermissionSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐 factor-adjust 四个权限点")
  void containsFactorAdjustPermissions() {
    assertThat(SQL).contains(
        "price:factor-adjust:list",
        "price:factor-adjust:detail",
        "price:factor-adjust:import",
        "price:factor-adjust:export");
  }

  @Test
  @DisplayName("联动价主导入复用 price:linked-item:import 且文案不混淆")
  void reusesLinkedImportPermissionWithClearLabel() {
    assertThat(SQL).contains(
        "price:linked-item:import",
        "导入月度联动价与影响因素 Excel",
        "导入月度调价 Excel",
        "导出调价模板");
  }

  @Test
  @DisplayName("管理员和业务角色默认授权，受限角色不自动授权")
  void grantsAdminAndBusinessRolesOnly() {
    assertThat(SQL).contains(
        "(1, 40280)", "(1, 40281)", "(1, 40282)", "(1, 40283)",
        "(10, 40280)", "(10, 40281)", "(10, 40282)", "(10, 40283)",
        "(11, 40280)", "(11, 40281)", "(11, 40282)", "(11, 40283)");
    assertThat(SQL).doesNotContain("(12, 40280)", "(12, 40281)", "(12, 40282)", "(12, 40283)");
  }

  private static String readSql() {
    try (var in = V81PriceFactorAdjustPermissionSqlTest.class.getResourceAsStream(
        "/db/V81__price_factor_adjust_permission_seed.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V81 SQL 失败", e);
    }
  }
}
