package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V250QuoteTechnicalDataSalaryFieldsSqlTest {

  @Test
  void addsOriginalRateUnitAndCoefficientWithoutAddingOrDroppingTables() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/V250__quote_technical_data_salary_fields.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("ADD COLUMN `wage_rate`", "ADD COLUMN `rate_unit`",
            "ADD COLUMN `person_coefficient`")
        .contains("SET `wage_rate` = `hourly_rate`")
        .contains("SET `rate_unit` = '元/小时'")
        .contains("MODIFY COLUMN `wage_rate` DECIMAL(20,8) NOT NULL")
        .contains("CHECK (`person_coefficient` > 0)")
        .doesNotContain("CREATE TABLE", "DROP TABLE", "DROP COLUMN", "cms_cost_source_effective`");
  }
}
