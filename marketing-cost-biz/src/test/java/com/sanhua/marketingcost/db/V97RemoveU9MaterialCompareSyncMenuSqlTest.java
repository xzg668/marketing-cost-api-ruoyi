package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V97 移除 U9 料品主档差异与同步菜单")
class V97RemoveU9MaterialCompareSyncMenuSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("删除废弃按钮权限菜单及角色授权")
  void removesDeprecatedPermissions() {
    assertThat(SQL).contains(
        "DELETE FROM sys_role_menu WHERE menu_id IN (40439, 40440)",
        "DELETE FROM sys_menu WHERE menu_id IN (40439, 40440)");
  }

  private static String readSql() {
    try (var in = V97RemoveU9MaterialCompareSyncMenuSqlTest.class.getResourceAsStream(
        "/db/V97__remove_u9_material_compare_sync_menu.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V97 SQL 失败", e);
    }
  }
}
