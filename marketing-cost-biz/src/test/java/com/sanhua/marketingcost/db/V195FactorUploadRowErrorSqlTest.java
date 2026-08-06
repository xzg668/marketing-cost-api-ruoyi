package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V195FactorUploadRowErrorSqlTest {

  @Test
  @DisplayName("V195新增批次行级失败明细并保留业务排查字段")
  void factorUploadRowErrorMigrationContract() throws Exception {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V195__factor_upload_row_error.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "CREATE TABLE IF NOT EXISTS lp_factor_upload_row_error",
        "factor_upload_batch_id",
        "excel_row_number",
        "material_code",
        "formula_effective_date",
        "error_code",
        "error_message",
        "suggestion",
        "idx_factor_upload_row_error_batch");
    assertThat(sql.toUpperCase()).doesNotContain("DROP TABLE", "DELETE FROM");
  }
}
