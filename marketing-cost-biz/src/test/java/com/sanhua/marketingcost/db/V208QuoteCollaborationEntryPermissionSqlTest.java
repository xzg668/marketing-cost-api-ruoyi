package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-08 报价协作入口权限迁移")
class V208QuoteCollaborationEntryPermissionSqlTest {
  @Test
  void addsOnlyButtonPermissionAndInheritsExistingBomCheckRoles() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/V208__quote_collaboration_entry_permission.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql).contains("collaboration:task:create");
    assertThat(sql).contains("menu_type").contains("'F'");
    assertThat(sql).contains("ingest:quote:bom-check");
    assertThat(sql).doesNotContain("CREATE TABLE");
    assertThat(sql).doesNotContain("OA报价单协同");
    assertThat(sql).doesNotContain("query_param", "route_name");
    assertThat(sql).doesNotContain("40483");
  }
}
