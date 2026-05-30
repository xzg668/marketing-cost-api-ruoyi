package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V127 月度调价业务单元锁唯一兜底")
class V127MonthlyRepriceActiveLockKeySqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("使用批次表生成列实现未结束批次锁，不新增锁表")
  void usesGeneratedColumnOnBatchTable() {
    assertThat(SQL).contains(
        "lp_monthly_reprice_batch",
        "active_lock_key",
        "GENERATED ALWAYS AS",
        "CREATED",
        "PREPARING",
        "RUNNING",
        "WAIT_CONFIRM",
        "UNIQUE KEY uk_monthly_reprice_active_bu (active_lock_key)");
    assertThat(SQL).doesNotContain("CREATE TABLE IF NOT EXISTS lp_system_lock");
  }

  @Test
  @DisplayName("迁移保持幂等")
  void isIdempotent() {
    assertThat(SQL).contains(
        "add_column_if_not_exists_v127",
        "add_index_if_not_exists_v127",
        "NOT EXISTS");
  }

  private static String readSql() {
    try (var in = V127MonthlyRepriceActiveLockKeySqlTest.class.getResourceAsStream(
        "/db/V127__monthly_reprice_active_lock_key.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V127 SQL 失败", e);
    }
  }
}
