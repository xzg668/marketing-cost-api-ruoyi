package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("QCBP-09 技术协作菜单迁移契约")
class V209TechnicalCollaborationMenuSqlTest {
  @Test
  void createsRestrictedTechnicalMenuWithoutUsersOrBusinessData() throws IOException {
    String sql = new ClassPathResource(
        "db/V209__technical_collaboration_menu_and_permissions.sql")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql).contains("'协作入口'")
        .contains("'技术协作'")
        .contains("'collaboration/technical/index'")
        .contains("'collaboration:task:read'")
        .contains("'collaboration:task:edit'")
        .contains("'collaboration:task:submit'")
        .contains("LOWER(role.role_key) = 'oa_collaborator'")
        .contains("DELETE role_menu")
        .doesNotContain("INSERT INTO sys_user")
        .doesNotContain("INSERT INTO lp_quote_collaboration_product_task")
        .doesNotContain("INSERT INTO lp_quote_collaboration_gap");
  }
}
