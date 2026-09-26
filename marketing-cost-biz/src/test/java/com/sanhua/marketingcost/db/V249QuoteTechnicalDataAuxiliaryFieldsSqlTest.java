package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V249QuoteTechnicalDataAuxiliaryFieldsSqlTest {

  @Test
  void addsExtensibleAuxiliaryFieldsBackfillsLegacyRowsAndKeepsExistingTables() throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
        "/db/V249__quote_technical_data_auxiliary_fields.sql")) {
      assertThat(stream).isNotNull();
      sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("ADD COLUMN `auxiliary_material_no`", "ADD COLUMN `auxiliary_spec`",
            "ADD COLUMN `price_unit`", "ADD COLUMN `loss_rate`")
        .contains("SET `auxiliary_material_no` = `subject_code`")
        .contains("SET `price_unit` = CONCAT('元/', `standard_unit`)")
        .contains("MODIFY COLUMN `auxiliary_material_no` VARCHAR(64) NOT NULL")
        .contains("MODIFY COLUMN `price_unit` VARCHAR(32) NOT NULL")
        .contains("CHECK (`loss_rate` >= 0 AND `loss_rate` <= 1)")
        .doesNotContain("DROP TABLE", "DROP COLUMN", "cms_cost_source_effective`");
  }
}
