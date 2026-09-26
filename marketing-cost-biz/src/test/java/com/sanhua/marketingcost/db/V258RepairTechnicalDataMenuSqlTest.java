package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V258RepairTechnicalDataMenuSqlTest {

  @Test
  void recreatesMissingTechnicalDataChildrenAndRoleGrantsIdempotently() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V258__repair_technical_data_menu.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
        .contains("'我的协作任务'", "'technical-data/tasks/index'", "'technical:data:task:list'")
        .contains("'补录审核'", "'technical-data/reviews/index'", "'technical:data:review:list'")
        .contains("'technical:data:task:edit'", "'technical:data:review:decide'")
        .contains("'technical:data:admin:operate'", "INSERT IGNORE INTO sys_role_menu")
        .contains("NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("DELETE FROM sys_menu");
  }
}
