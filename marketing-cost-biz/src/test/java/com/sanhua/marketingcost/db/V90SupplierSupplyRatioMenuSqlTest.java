package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V90 供应商供货比例菜单 seed")
class V90SupplierSupplyRatioMenuSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("菜单挂在基础数据下的供应关系目录")
  void createsBaseSupplierRelationMenu() {
    assertThat(SQL).contains(
        "(40159, '基础数据'",
        "(40426, '供应关系', 40159",
        "(40427, '供应商供货比例', 40426",
        "'/base/supplier-relation'",
        "'/base/supplier-relation/supply-ratio'",
        "'pages:SupplierSupplyRatioPage'");
  }

  @Test
  @DisplayName("页面和按钮权限覆盖查询、导入、编辑、删除")
  void seedsPageAndButtonPermissions() {
    assertThat(SQL).contains(
        "base:supplier-supply-ratio:list",
        "base:supplier-supply-ratio:import",
        "base:supplier-supply-ratio:edit",
        "base:supplier-supply-ratio:remove",
        "供应商供货比例导入",
        "供应商供货比例逻辑删除权限");
  }

  @Test
  @DisplayName("兼容已有基础数据根菜单差异，并为业务角色授权")
  void isCompatibleWithExistingBaseMenuAndRoleGrants() {
    assertThat(SQL).contains(
        "V90：供应关系菜单所需基础数据根菜单",
        "ON DUPLICATE KEY UPDATE",
        "(1, 40159)",
        "(10, 40427)",
        "(11, 40431)",
        "已能看到基础数据的角色，自动补齐供应关系菜单和页面权限",
        "WHERE menu_id IN (40159)");
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
    try (var in = V90SupplierSupplyRatioMenuSqlTest.class.getResourceAsStream(
        "/db/V90__supplier_supply_ratio_menu.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V90 SQL 失败", e);
    }
  }
}
