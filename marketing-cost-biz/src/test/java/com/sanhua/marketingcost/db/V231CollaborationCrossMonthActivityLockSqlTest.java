package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V231 跨报价跨月份活动任务锁迁移")
class V231CollaborationCrossMonthActivityLockSqlTest {

  @Test
  @DisplayName("迁移沿用现有字段并落实料号、型号、临时键三级优先级")
  void migratesExistingLockWithoutAddingSchema() throws IOException {
    String sql = readMigration();

    assertThat(sql)
        .contains("QCBP-ACTIVE-V3:")
        .contains("VERSION=3")
        .contains("product_code")
        .contains("product_model")
        .contains("temporary_product_key")
        .contains("business_unit_type")
        .contains("applicable_org_code")
        .contains("WHERE active_flag = 1")
        .contains("WHERE active_flag = 0")
        .doesNotContain("ALTER TABLE")
        .doesNotContain("CREATE TABLE");
  }

  private String readMigration() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/V231__collaboration_cross_month_activity_lock.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
