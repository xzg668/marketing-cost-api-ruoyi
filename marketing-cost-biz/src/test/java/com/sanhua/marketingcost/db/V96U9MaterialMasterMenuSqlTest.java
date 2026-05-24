package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V96 U9料品主档菜单 seed")
class V96U9MaterialMasterMenuSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("菜单挂在基础数据下的 U9 基础数据目录")
  void createsU9MaterialMasterMenu() {
    assertThat(SQL).contains(
        "这是 U9 数据统一目录",
        "后续 U9 物料、BOM、组织、供应商等数据继续挂在",
        "(40159, '基础数据'",
        "(40435, 'U9基础数据', 40159",
        "(40436, '料品主档', 40435",
        "'/base/u9'",
        "'/base/u9/material-master'",
        "'pages:U9MaterialMasterPage'");
  }

  @Test
  @DisplayName("页面和按钮权限与 U9MM-06 后端权限点一致")
  void seedsPageAndButtonPermissions() {
    assertThat(SQL).contains(
        "base:u9-material:list",
        "base:u9-material:import",
        "base:u9-material:export",
        "U9 料品主档模板映射和导出权限");
    assertThat(SQL)
        .doesNotContain("base:u9-material:compare")
        .doesNotContain("base:u9-material:sync")
        .doesNotContain("料品主档差异对比")
        .doesNotContain("料品主档同步");
  }

  @Test
  @DisplayName("兼容已有基础数据根菜单，并为业务角色授权")
  void grantsRolesAndExistingBaseUsers() {
    assertThat(SQL).contains(
        "V96：U9 数据统一目录所需基础数据根菜单",
        "ON DUPLICATE KEY UPDATE",
        "(1, 40436)",
        "(11, 40441)",
        "已能看到基础数据的角色，自动补齐 U9 基础数据目录",
        "WHERE menu_id IN (40159)",
        "WHERE menu_id IN (40159, 40435)");
  }

  @Test
  @DisplayName("seed 幂等且不删除现有菜单")
  void isIdempotentAndNonDestructive() {
    assertThat(SQL)
        .contains("INSERT IGNORE INTO sys_role_menu")
        .contains("ON DUPLICATE KEY UPDATE")
        .doesNotContain("DELETE FROM sys_menu")
        .doesNotContain("DELETE FROM sys_role_menu")
        .doesNotContain("TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V96U9MaterialMasterMenuSqlTest.class.getResourceAsStream(
        "/db/V96__u9_material_master_menu.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V96 SQL 失败", e);
    }
  }
}
