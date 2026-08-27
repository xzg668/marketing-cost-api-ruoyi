package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V232 BOM 补录缺口说明扩容迁移")
class V232WidenBomSupplementMissingReasonSqlTest {

  @Test
  @DisplayName("只扩容现有缺口字段，不增加表或业务字段")
  void widensOnlyMissingReason() throws IOException {
    String sql = readMigration();

    assertThat(sql)
        .contains("ALTER TABLE lp_bom_supplement_task")
        .contains("missing_reason VARCHAR(1000)")
        .doesNotContain("CREATE TABLE")
        .doesNotContain("ADD COLUMN");
  }

  private String readMigration() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/V232__widen_bom_supplement_missing_reason.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
