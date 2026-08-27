package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V239HardenQuoteCostingExecutionSqlTest {

  @Test
  void migrationRemovesLegacyChainAndAddsOnlyTheThreeApprovedTables() throws IOException {
    String sql =
        new ClassPathResource("db/V239__harden_quote_costing_execution.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("'ingest:quote:cost-run:execute'")
        .contains("DROP COLUMN `prerequisite_status`")
        .contains("CREATE TABLE IF NOT EXISTS `lp_cost_business_rule`")
        .contains("CREATE TABLE IF NOT EXISTS `lp_cost_run_execution_history`")
        .contains("CREATE TABLE IF NOT EXISTS `lp_cost_run_task_history`")
        .contains("'CMS_AUX_UPLIFT_RATE'")
        .contains("'PACKAGE_COMPONENT_COEFFICIENT'")
        .doesNotContain("prerequisite_status` VARCHAR")
        .doesNotContain("CREATE TABLE IF NOT EXISTS `tmp_");
  }
}
