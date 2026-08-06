package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QEB 菜单前置图标清理 SQL")
class V204MenuIconCleanupSqlTest {

  @Test
  @DisplayName("只清除报价BOM形态规则和CMS科目设置菜单图标")
  void clearsOnlyRequestedMenuIcons() throws Exception {
    String sql = readSql();

    assertThat(sql).contains(
        "SET icon = '#'",
        "WHERE menu_id = 40483",
        "AND menu_name = '报价 BOM 物料形态规则'",
        "WHERE menu_id = 40239",
        "AND menu_name = 'CMS 科目设置'");
  }

  @Test
  @DisplayName("不修改菜单权限和业务数据")
  void doesNotMutatePermissionsOrBusinessData() throws Exception {
    String upper = readSql().toUpperCase();

    assertThat(upper).doesNotContain(
        "SYS_ROLE_MENU",
        "LP_MATERIAL_QUOTE_SHAPE_POLICY",
        "LP_QUOTE_EFFECTIVE_BOM_NODE",
        "LP_QUOTE_BOM_MONTHLY_SNAPSHOT",
        "LP_BOM_COSTING_ROW",
        "LP_COST_RUN_RESULT");
  }

  private static String readSql() throws Exception {
    try (InputStream input = V204MenuIconCleanupSqlTest.class
        .getResourceAsStream("/db/V204__hide_quote_shape_and_cms_subject_menu_icons.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
