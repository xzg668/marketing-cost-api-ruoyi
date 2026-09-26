package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("V245 制造费用率事业部型号键 SQL 契约")
class V245ManufactureRateDivisionModelSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("型号级历史规则迁移为事业部加型号且不删除业务数据")
  void migratesModelKeysWithoutDeletingRates() {
    assertThat(SQL)
        .contains(
            "CONCAT(TRIM(business_division), '::', TRIM(product_model))",
            "match_level = 'MATERIAL_MODEL'",
            "CONCAT('LEGACY-MODEL-', id)")
        .doesNotContainIgnoringCase("DELETE FROM")
        .doesNotContainIgnoringCase("DROP TABLE");
  }

  private static String readSql() {
    try {
      return new ClassPathResource("db/V245__scope_manufacture_model_rate_by_division.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V245 SQL 失败", exception);
    }
  }
}
