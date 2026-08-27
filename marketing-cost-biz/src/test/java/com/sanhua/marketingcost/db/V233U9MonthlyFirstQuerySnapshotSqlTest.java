package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V233U9MonthlyFirstQuerySnapshotSqlTest {

  @Test
  void addsOneNullableIdentityKeyToExistingMonthlyTable() throws IOException {
    String sql = read();

    assertThat(sql)
        .contains("ALTER TABLE lp_quote_bom_monthly_snapshot")
        .contains("business_unit_type")
        .contains("material_organization_code")
        .contains("snapshot_identity_key CHAR(64) DEFAULT NULL")
        .contains("structure_fingerprint")
        .contains("line_count")
        .contains("UNIQUE KEY uk_quote_bom_u9_monthly_identity")
        .doesNotContain("CREATE TABLE")
        .doesNotContain("DROP TABLE");
  }

  private String read() throws IOException {
    try (InputStream stream = getClass().getResourceAsStream(
        "/db/V233__u9_monthly_first_query_snapshot.sql")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
