package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("V243 核算明细结构位置唯一键 SQL 契约")
class V243CostingRowOccurrenceUniqueKeySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("只调整既有核算明细唯一键且纳入稳定路径")
  void replacesMaterialOnlyUniquenessWithOccurrenceUniqueness() {
    assertThat(SQL).contains(
        "ALTER TABLE lp_bom_costing_row",
        "DROP INDEX uk_bom_costing_item_material_version",
        "ADD UNIQUE KEY uk_bom_costing_item_material_version",
        "material_code",
        "path",
        "as_of_date",
        "raw_version_effective_from");
    assertThat(SQL)
        .doesNotContainIgnoringCase("CREATE TABLE")
        .doesNotContainIgnoringCase("DROP TABLE")
        .doesNotContainIgnoringCase("DELETE FROM")
        .doesNotContainIgnoringCase("UPDATE lp_bom_costing_row");
  }

  private static String readSql() {
    try {
      return new ClassPathResource("db/V243__costing_row_preserve_bom_occurrence.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V243 SQL 失败", exception);
    }
  }
}
