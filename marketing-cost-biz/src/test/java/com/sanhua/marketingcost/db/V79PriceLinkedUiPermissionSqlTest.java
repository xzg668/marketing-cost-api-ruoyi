package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V79 联动价/影响因素按钮权限 seed")
class V79PriceLinkedUiPermissionSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("联动价导入、历史、增删改和影响因素权限点命名清晰")
  void containsExpectedPermissions() {
    assertThat(SQL).contains(
        "price:linked-item:list",
        "price:linked-item:import",
        "price:linked-item:import-history:list",
        "price:linked-item:add",
        "price:linked-item:edit",
        "price:linked-item:remove",
        "price:finance-base:list",
        "price:finance-base:edit",
        "price:finance-base:batch:list");
  }

  @Test
  @DisplayName("管理员和业务角色持有新增按钮权限，受限角色不自动授权")
  void grantsAdminAndBusinessRolesOnly() {
    assertThat(SQL).contains("(1, 40158)", "(10, 40158)", "(11, 40158)");
    assertThat(SQL).doesNotContain("(12, 40158)");
  }

  @Test
  @DisplayName("旧影响因素导入权限改名为补导语义，前端第一期可隐藏")
  void updatesLegacyFactorImportName() {
    assertThat(SQL).contains("影响因素补导", "price:finance-base:import");
  }

  private static String readSql() {
    try (var in = V79PriceLinkedUiPermissionSqlTest.class.getResourceAsStream(
        "/db/V79__price_linked_ui_permission_cleanup.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V79 SQL 失败", e);
    }
  }
}
