package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-27 禁用应用内 BOM 导入和构建菜单")
class V215DisableApplicationBomImportMenuSqlTest {

  @Test
  void disablesLegacyImportMenuAndKeepsSchemaUntouched() throws Exception {
    String sql;
    try (var input =
        getClass().getResourceAsStream("/db/V215__disable_application_bom_import_menu.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("UPDATE sys_menu", "visible = '1'", "status = '1'", "bom-data:u9-raw:list")
        .doesNotContain("ALTER TABLE", "DROP TABLE", "DELETE FROM");
  }
}
