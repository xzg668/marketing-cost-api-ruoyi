package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V247TechnicalDataWorkbenchMenuSqlTest {
  private static final Path MIGRATION = Path.of(
      "src/main/resources/db/V247__technical_data_workbench_menu.sql");

  @Test
  void switchesOnlyAuthenticatedTaskMenuToNewComponentAndPermissions() throws Exception {
    String sql = Files.readString(MIGRATION);
    assertThat(sql)
        .contains("component='technical-data/tasks/index'")
        .contains("perms='technical:data:task:list'")
        .contains("perms='technical:data:task:edit'")
        .contains("本人负责的报价技术资料补录任务")
        .doesNotContain("DELETE FROM sys_menu")
        .doesNotContain("lp_quote_collaboration_")
        .doesNotContain("DROP TABLE");
  }
}
