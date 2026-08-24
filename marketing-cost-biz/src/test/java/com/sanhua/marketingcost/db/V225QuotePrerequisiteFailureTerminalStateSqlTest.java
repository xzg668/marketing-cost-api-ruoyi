package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V225 报价前置失败终态修复")
class V225QuotePrerequisiteFailureTerminalStateSqlTest {

  private static final String SQL = readSql();

  @Test
  void closesPendingTasksAndBatchWithoutTouchingSuccessfulTasks() {
    assertThat(SQL)
        .contains("b.prerequisite_status = 'FAILED'")
        .contains("t.status IN ('PENDING', 'RETRYABLE')")
        .contains("SET t.status = 'FAILED'")
        .contains("SET b.status = 'FAILED'")
        .contains("b.progress = 100")
        .doesNotContain("DELETE FROM");
  }

  private static String readSql() {
    try (InputStream input = V225QuotePrerequisiteFailureTerminalStateSqlTest.class
        .getResourceAsStream("/db/V225__quote_prerequisite_failure_terminal_state.sql")) {
      assertThat(input).isNotNull();
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (Exception exception) {
      throw new IllegalStateException("读取 V225 SQL 失败", exception);
    }
  }
}
