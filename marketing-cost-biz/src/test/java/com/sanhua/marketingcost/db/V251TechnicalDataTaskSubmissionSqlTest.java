package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V251TechnicalDataTaskSubmissionSqlTest {
  @Test
  void addsSubmissionIdempotencyAndAggregateFingerprintWithoutAddingTables() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V251__quote_technical_data_task_submission.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("submission_idempotency_key", "submission_fingerprint")
        .contains("idx_quote_tech_task_submission_key")
        .contains("trg_quote_tech_product_bu_submitted_guard")
        .contains("trg_quote_tech_module_bu_submitted_guard")
        .doesNotContainIgnoringCase("CREATE TABLE");
  }
}
