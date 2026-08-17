package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("QCBP-19 财务补录审核菜单迁移契约")
class V211FinanceCollaborationReviewMenuSqlTest {
  @Test
  void grantsOnlyReviewMenuToExplicitFinanceRoleWithoutCreatingUsers() throws IOException {
    String sql = new ClassPathResource("db/V211__finance_collaboration_review_menu.sql")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql).contains("'finance_reviewer'")
        .contains("'补录审核'")
        .contains("'collaboration/finance/index'")
        .contains("'collaboration:review:read'")
        .contains("'collaboration:review:decide'")
        .contains("INSERT IGNORE INTO sys_role_menu")
        .doesNotContain("INSERT INTO sys_user")
        .doesNotContain("INSERT INTO lp_quote_collaboration_review");
  }
}
