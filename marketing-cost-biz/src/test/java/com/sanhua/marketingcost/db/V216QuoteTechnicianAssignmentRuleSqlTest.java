package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("QCBP-28 技术负责人匹配规则表迁移")
class V216QuoteTechnicianAssignmentRuleSqlTest {
  @Test
  void createsOnlyOneAdditiveRuleTable() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V216__quote_technician_assignment_rule.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertThat(sql)
        .contains("CREATE TABLE IF NOT EXISTS lp_quote_technician_assignment_rule")
        .contains("technician_user_id BIGINT NOT NULL")
        .contains("technician_oa_user_id")
        .contains("technician_job_no")
        .contains("uk_qc_technician_rule_code")
        .doesNotContain("ALTER TABLE", "DROP TABLE", "DELETE FROM", "INSERT INTO");
    assertThat(sql.split("CREATE TABLE", -1)).hasSize(2);
  }
}
