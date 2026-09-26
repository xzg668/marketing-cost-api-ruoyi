package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V290TechnicalDataUnassignedPreparationSqlTest {
  @Test
  void permitsAnUnassignedTaskWithoutInventingATechnician() throws Exception {
    String sql = new ClassPathResource("db/V290__technical_data_unassigned_preparation.sql")
        .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("MODIFY assignee_user_id BIGINT NULL")
        .contains("'UNASSIGNED'")
        .contains("ck_quote_tech_task_assignee_pair")
        .contains("ck_quote_tech_task_unassigned_owner")
        .doesNotContain("CREATE TABLE");
  }
}
