package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class V254TechnicalDataOaAdminSecuritySqlTest {
  @Test
  void keepsOaOrderingProxyBindingAndSingleUseLedgerInsideExistingTables() throws Exception {
    String sql;
    try (var input = getClass().getResourceAsStream(
        "/db/V254__technical_data_oa_admin_security.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql).contains(
        "external_callback_seq",
        "external_active_lock_key",
        "external_last_event_id",
        "external_retry_count",
        "external_next_retry_at",
        "proxy_operator_user_id",
        "proxy_reason",
        "proxy_request_id",
        "uk_quote_tech_task_external_identity",
        "idempotency_key",
        "uk_business_change_idempotency",
        "technical:data:admin:operate");
    assertThat(sql)
        .doesNotContainIgnoringCase("CREATE TABLE")
        .doesNotContain("lp_collaboration_token");
  }
}
