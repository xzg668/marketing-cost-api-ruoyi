package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V212CollaborationMenuScopeEntriesSqlTest {
  @Test
  void keepsOneParentAndOneTaskModelWithTwoSimpleTechnicalEntries() throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/V212__collaboration_menu_scope_entries.sql"),
        StandardCharsets.UTF_8);
    assertThat(sql).contains("'BOM技术协作'")
        .contains("'补价协作'")
        .contains("'collaboration/technical/index'")
        .contains("path='finance-reviews'")
        .contains("LOWER(role.role_key)='oa_collaborator'")
        .doesNotContain("INSERT INTO sys_user")
        .doesNotContain("OA报价单协同");
  }
}
