package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V236RemoveLegacyBomPreparationWorkflowSqlTest {

  @Test
  void keepsOneInternalWorkflowAndRemovesLegacyAndUnusedOaShells() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V236__remove_legacy_bom_preparation_workflow.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
        .contains("ingest/quote-request-products/bom/index")
        .contains("DELETE FROM lp_collaboration_token")
        .contains("role_key = 'technical_collaborator'")
        .contains("INSERT INTO lp_business_change_log")
        .contains("'PRODUCT_TASK_EVENT'")
        .contains("DROP COLUMN task_id")
        .contains("DROP COLUMN supplement_task_id")
        .contains("DROP COLUMN source_task_id")
        .contains("DROP TABLE lp_bom_supplement_todo")
        .contains("DROP TABLE lp_bom_supplement_task_quote_link")
        .contains("DROP TABLE lp_bom_supplement_task")
        .contains("DROP TABLE lp_integration_inbox")
        .contains("DROP TABLE lp_integration_outbox")
        .contains("DROP TABLE lp_quote_collaboration_external_task");
  }
}
