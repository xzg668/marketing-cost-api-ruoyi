package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V144 报价产品 BOM 准备菜单重构兼容修复")
class V144QuoteBomPreparationMenuRepairSqlTest {

  private static final String SQL =
      readSql("/db/V144__quote_bom_preparation_menu_restructure_repair.sql");

  @Test
  @DisplayName("兼容历史快照中的 BOM 菜单 ID")
  void supportsSnapshotMenuIds() {
    assertThat(SQL).contains(
        "menu_id IN (202, 40167)",
        "menu_id IN (40170)",
        "menu_id IN (301, 40171)",
        "menu_id IN (302, 40172)",
        "menu_id IN (40158, 4015)",
        "menu_id IN (203, 204, 40168, 40169)");
  }

  @Test
  @DisplayName("目标菜单结构仍落到 BOM 数据管理下")
  void keepsTargetTree() {
    assertThat(SQL).contains(
        "40461, 'BOM 数据管理', 0",
        "40462, 'BOM 明细', 40461",
        "parent_id = 40462",
        "menu_name = 'BOM 明细过滤规则'",
        "menu_name = 'BOM 结算明细'",
        "40464, '包装组件结构', 40462",
        "40467, 'BOM 补录任务', 40461",
        "40468, '补录 BOM 版本', 40461");
  }

  @Test
  @DisplayName("修复只隐藏旧入口不删除菜单和授权")
  void doesNotDeleteExistingMenus() {
    assertThat(SQL)
        .doesNotContain("DELETE FROM sys_menu")
        .doesNotContain("DELETE FROM sys_role_menu")
        .doesNotContain("TRUNCATE TABLE")
        .contains("visible = '1'")
        .contains("INSERT IGNORE INTO sys_role_menu");
  }

  private static String readSql(String resource) {
    try (var in = V144QuoteBomPreparationMenuRepairSqlTest.class.getResourceAsStream(resource)) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 SQL 失败: " + resource, e);
    }
  }
}
