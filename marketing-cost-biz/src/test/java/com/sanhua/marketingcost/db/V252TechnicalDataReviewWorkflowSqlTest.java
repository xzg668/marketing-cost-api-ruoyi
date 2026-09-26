package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V252TechnicalDataReviewWorkflowSqlTest {
  @Test
  void addsReviewInheritanceLockAndSwitchesMenuWithoutAddingTables() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V252__technical_data_review_workflow.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("inherited_from_review_item_id", "row_version")
        .contains("technical-data/reviews/index")
        .contains("technical:data:review:list", "technical:data:review:decide")
        .doesNotContainIgnoringCase("CREATE TABLE");
  }
}
