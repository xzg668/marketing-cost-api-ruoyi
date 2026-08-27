package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V234ConsolidateCollaborationTaskMenuSqlTest {
  @Test
  void keepsOneTechnicalTaskEntryAndGrantsOwnedReviewsToCostingRoles() throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/V234__consolidate_collaboration_task_menu.sql"),
        StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("menu_name='协作任务'")
        .contains("menu_name='我的协作任务'")
        .contains("path='prices'")
        .contains("DELETE FROM sys_role_menu WHERE menu_id=@obsolete_price_menu")
        .contains("DELETE FROM sys_menu WHERE menu_id=@obsolete_price_menu")
        .contains("LOWER(role.role_key) IN ('bu_staff', 'bu_director')")
        .contains("finance_reviewer_user_id=created_by")
        .contains("@finance_review_menu")
        .contains("@finance_review_decide")
        .doesNotContain("INSERT INTO sys_user");
  }
}
