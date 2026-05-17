package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V82 上传批次导入策略字段修复 DDL")
class V82FactorUploadBatchStrategyColumnsRepairSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("补齐 lp_factor_upload_batch 的导入用途和生效策略字段")
  void repairsMissingStrategyColumns() {
    assertThat(SQL).contains(
        "lp_factor_upload_batch",
        "import_purpose",
        "effective_strategy",
        "LINKED_APPEND_ONLY",
        "LINKED_OVERRIDE_EFFECTIVE",
        "APPEND_ONLY/OVERRIDE_EFFECTIVE");
  }

  @Test
  @DisplayName("修复脚本幂等，并且表不存在时不会执行 ALTER")
  void isIdempotentAndTableAware() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE add_column_if_not_exists_v82",
        "CREATE PROCEDURE add_index_if_not_exists_v82",
        "information_schema.TABLES",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS add_column_if_not_exists_v82");
  }

  @Test
  @DisplayName("补齐 import_purpose/effective_strategy 组合索引")
  void repairsPurposeStrategyIndex() {
    assertThat(SQL).contains(
        "idx_factor_upload_purpose_strategy",
        "KEY idx_factor_upload_purpose_strategy (import_purpose, effective_strategy)");
  }

  private static String readSql() {
    try (var in = V82FactorUploadBatchStrategyColumnsRepairSqlTest.class.getResourceAsStream(
        "/db/V82__factor_upload_batch_strategy_columns_repair.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V82 SQL 失败", e);
    }
  }
}
