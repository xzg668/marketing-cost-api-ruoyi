package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V289OaPersonDirectorySqlTest {
  @Test
  void createsOneDirectoryTableWithCsvIdentityAndDepartmentFields() throws Exception {
    try (var input = getClass().getResourceAsStream("/db/V289__oa_person_directory.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
      assertThat(sql).containsOnlyOnce("create table");
      assertThat(sql).contains("lp_oa_person_directory", "employee_no", "person_name",
          "target_department_paths", "actual_department_paths", "oa_user_id",
          "oa_department_ids", "position_name", "employment_status", "sync_batch_id");
    }
  }
}
