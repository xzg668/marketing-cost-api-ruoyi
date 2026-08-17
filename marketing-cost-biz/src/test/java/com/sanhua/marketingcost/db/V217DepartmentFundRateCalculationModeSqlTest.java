package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("部门经费率费率口径迁移")
class V217DepartmentFundRateCalculationModeSqlTest {

  @Test
  void preservesExistingRowsAsLegacyAndDefaultsNewRowsToFinalQuote() throws Exception {
    String sql;
    try (var input =
        getClass()
            .getResourceAsStream("/db/V217__department_fund_rate_calculation_mode.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
        .contains("rate_calculation_mode")
        .contains("SET rate_calculation_mode = 'PLAN_UPLIFT'")
        .contains("NOT NULL DEFAULT 'FINAL_QUOTE'")
        .doesNotContain("DROP TABLE", "DELETE FROM");
  }
}
