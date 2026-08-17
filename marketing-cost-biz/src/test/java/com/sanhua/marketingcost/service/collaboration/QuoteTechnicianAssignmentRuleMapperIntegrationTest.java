package com.sanhua.marketingcost.service.collaboration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.mapper.QuoteTechnicianAssignmentRuleMapper;
import com.sanhua.marketingcost.mapper.bom.BomMapperTestBase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@Tag("integration")
class QuoteTechnicianAssignmentRuleMapperIntegrationTest extends BomMapperTestBase {
  @Autowired private QuoteTechnicianAssignmentRuleMapper mapper;
  @Autowired private JdbcTemplate jdbcTemplate;

  private final String prefix = "QCBP28-" + UUID.randomUUID().toString().substring(0, 8);

  @BeforeAll
  static void createSchema() throws Exception {
    try (Connection connection = openConnection();
        Statement statement = connection.createStatement();
        InputStream input = QuoteTechnicianAssignmentRuleMapperIntegrationTest.class
            .getResourceAsStream("/db/V216__quote_technician_assignment_rule.sql")) {
      assertThat(input).isNotNull();
      String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      for (String fragment : sql.split(";")) {
        if (!fragment.isBlank()) statement.execute(fragment);
      }
    }
  }

  @AfterEach
  void clean() {
    jdbcTemplate.update(
        "DELETE FROM lp_quote_technician_assignment_rule WHERE rule_code LIKE ?", prefix + "%");
  }

  @Test
  void queryReturnsOnlyCurrentEnabledBusinessUnitRules() {
    LocalDate today = LocalDate.of(2026, 8, 15);
    insert("-ACTIVE", "COMMERCIAL", "ENABLED", "2026-08-01", "2026-08-31", 601L);
    insert("-EXPIRED", "COMMERCIAL", "ENABLED", "2026-07-01", "2026-07-31", 602L);
    insert("-DISABLED", "COMMERCIAL", "DISABLED", null, null, 603L);
    insert("-OTHER-BU", "HOUSEHOLD", "ENABLED", null, null, 604L);

    assertThat(mapper.selectEffectiveRules("COMMERCIAL", today))
        .extracting(rule -> rule.getRuleCode())
        .containsExactly(prefix + "-ACTIVE");
  }

  private void insert(String suffix, String businessUnit, String status,
      String effectiveFrom, String effectiveTo, Long userId) {
    jdbcTemplate.update("""
        INSERT INTO lp_quote_technician_assignment_rule
          (rule_code, rule_name, business_unit_type, technician_user_id, priority, status,
           effective_from, effective_to, source_type, created_by)
        VALUES (?, ?, ?, ?, 100, ?, ?, ?, 'MANUAL', 'QCBP-28-TEST')
        """, prefix + suffix, suffix, businessUnit, userId, status, effectiveFrom, effectiveTo);
  }
}
