package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V253CostRunTechnicalTraceSqlTest {

  @Test
  void bindsCostVersionToOneReviewedTechnicalInputWithoutCreatingTables() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V253__cost_run_effective_technical_data_trace.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
        .contains(
            "tech_data_version_id",
            "tech_data_version_no",
            "tech_data_source",
            "tech_data_input_json",
            "tech_data_retrieved_at",
            "fk_quote_cost_run_tech_version",
            "ck_quote_cost_run_tech_trace",
            "QUOTE_TECH_EFFECTIVE_VERSION")
        .contains("ON DELETE RESTRICT")
        .doesNotContainIgnoringCase("CREATE TABLE");
  }
}
