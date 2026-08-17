package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-07 审核结果生成幂等迁移")
class V207ApprovedResultIdempotencySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("只在现有审核结果表增加来源审核唯一键，不写业务或模拟数据")
  void addsOnlyApprovedResultSourceUniqueness() {
    String executable = SQL.lines()
        .filter(line -> !line.stripLeading().startsWith("--"))
        .reduce("", (left, right) -> left + "\n" + right)
        .toUpperCase();

    assertThat(SQL).contains(
        "ALTER TABLE `lp_quote_collaboration_approved_result`",
        "UNIQUE KEY `uk_approved_result_source`",
        "(`source_product_task_id`, `source_review_id`, `result_type`)");
    assertThat(executable).doesNotContain(
        "INSERT INTO", "UPDATE ", "DELETE FROM", "DROP TABLE", "TRUNCATE TABLE");
  }

  private static String readSql() {
    try (var in = V207ApprovedResultIdempotencySqlTest.class.getResourceAsStream(
        "/db/V207__quote_collaboration_approved_result_idempotency.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V207 SQL 失败", exception);
    }
  }
}
