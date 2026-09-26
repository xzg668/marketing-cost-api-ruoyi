package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V248TechnicalDataImmutabilitySqlTest {

  @Test
  void migrationProtectsVersionsAndAllThreeDetailTables() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V248__quote_technical_data_version_immutability.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("trg_quote_tech_version_bu_immutable")
        .contains("trg_quote_tech_version_bd_immutable")
        .contains("TECH_DATA_VERSION_IMMUTABLE")
        .contains("OLD.`version_status` = 'DRAFT'")
        .contains("OLD.`version_status` = 'SUBMITTED'");
    for (String type : new String[] {"package", "aux", "salary"}) {
      assertThat(sql)
          .contains("trg_quote_tech_" + type + "_bi_draft")
          .contains("trg_quote_tech_" + type + "_bu_draft")
          .contains("trg_quote_tech_" + type + "_bd_draft");
    }
  }
}
