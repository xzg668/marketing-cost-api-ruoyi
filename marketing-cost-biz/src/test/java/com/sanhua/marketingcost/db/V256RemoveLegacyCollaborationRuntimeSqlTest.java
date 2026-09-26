package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V256RemoveLegacyCollaborationRuntimeSqlTest {
  @Test
  void movesElectronicWorkflowToSharedPreparationAndRemovesOldPermissions() throws IOException {
    String sql = new String(getClass().getResourceAsStream(
        "/db/V256__remove_legacy_collaboration_runtime.sql").readAllBytes(),
        StandardCharsets.UTF_8);

    assertThat(sql).contains(
        "ALTER TABLE lp_quote_bom_preparation_record",
        "electronic_workflow_version",
        "electronic_workflow_stage",
        "electronic_source_version_id",
        "electronic_composition_fingerprint",
        "JOIN lp_quote_bom_supplement_version",
        "SET status='WAITING_INPUT'",
        "WHERE status='COLLABORATION'",
        "menu.perms LIKE 'collaboration:%'",
        "DELETE FROM sys_menu");
    assertThat(sql).doesNotContain(
        "DROP TABLE lp_quote_bom_preparation_record",
        "DROP TABLE lp_quote_bom_supplement_version");
  }
}
