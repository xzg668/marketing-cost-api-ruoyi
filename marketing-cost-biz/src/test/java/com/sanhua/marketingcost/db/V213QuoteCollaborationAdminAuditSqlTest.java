package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V213QuoteCollaborationAdminAuditSqlTest {
  @Test
  void compensationAuditIsAdditiveAndIdempotent() throws Exception {
    String sql = Files.readString(Path.of(
        "src/main/resources/db/V213__quote_collaboration_admin_audit.sql"),
        StandardCharsets.UTF_8);
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_quote_collaboration_admin_action")
        .contains("UNIQUE KEY uk_qc_admin_action_request (request_key)")
        .contains("operator_user_id")
        .contains("trace_id")
        .doesNotContain("ALTER TABLE lp_quote_collaboration_task")
        .doesNotContain("ALTER TABLE lp_bom_supplement_task");
  }
}
