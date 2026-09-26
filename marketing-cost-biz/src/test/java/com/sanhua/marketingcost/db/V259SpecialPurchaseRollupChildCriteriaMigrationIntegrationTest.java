package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sanhua.marketingcost.support.FinancePurchaseRollupRuleTestSupport;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;

@Tag("integration")
@DisplayName("V259 真实MySQL验证子件筛选规则替换与工作区失效")
class V259SpecialPurchaseRollupChildCriteriaMigrationIntegrationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("替换旧第五项、保留前四项和历史版本，重复执行不再使新草稿失效")
  void replacesOnlyRuleDAndInvalidatesOldWorkspacesOnce() throws Exception {
    try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
        .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_unicode_ci")) {
      mysql.start();
      try (Connection connection = DriverManager.getConnection(
          mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
          Statement statement = connection.createStatement()) {
        statement.execute("""
            CREATE TABLE lp_bom_settlement_rule (
              rule_code VARCHAR(128) PRIMARY KEY, rule_name VARCHAR(255),
              match_condition_json JSON, remark VARCHAR(1000),
              enabled INT, deleted INT, updated_by VARCHAR(64), updated_at DATETIME)
            """);
        statement.execute("""
            CREATE TABLE lp_quote_costing_workspace (
              id INT PRIMARY KEY, workspace_status VARCHAR(32), current_step VARCHAR(64),
              current_bom_build_batch_id VARCHAR(64), stale_reason_code VARCHAR(64),
              last_error_step VARCHAR(64), last_error_code VARCHAR(64), last_error_message VARCHAR(255),
              current_cost_version_id BIGINT, lock_version INT, updated_at DATETIME)
            """);
        statement.execute("""
            INSERT INTO lp_bom_settlement_rule (rule_code) VALUES
              ('SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION'), ('UNRELATED_RULE')
            """);
        ScriptUtils.executeSqlScript(connection,
            new ClassPathResource("db/V230__special_purchase_rollup_parent_criteria.sql"));
        JsonNode oldRule = readRule(statement);
        statement.execute("""
            INSERT INTO lp_quote_costing_workspace
              (id,workspace_status,current_step,current_bom_build_batch_id,current_cost_version_id,lock_version)
            VALUES (1,'BOM_READY','PRICE_TYPE_CONFIRMATION','OLD-DRAFT',NULL,3),
                   (2,'SUCCESS','RESULT','FROZEN-BUILD',99,8),
                   (3,'WAIT_BOM','QUOTE_BOM',NULL,NULL,0)
            """);

        applyMigration(connection);
        JsonNode newRule = readRule(statement);
        assertThat(newRule.get("nodeConditions")).isEqualTo(oldRule.get("nodeConditions"));
        assertThat(newRule.get("parentConditions")).isEqualTo(oldRule.get("parentConditions"));
        assertThat(newRule).isEqualTo(objectMapper.readTree(
            FinancePurchaseRollupRuleTestSupport.rule().getMatchConditionJson()));
        assertThat(singleValue(statement,
            "SELECT updated_by FROM lp_bom_settlement_rule WHERE rule_code='UNRELATED_RULE'"))
            .isNull();
        assertThat(singleValue(statement, """
            SELECT GROUP_CONCAT(CONCAT(id,':',workspace_status,':',current_step,':',
              COALESCE(stale_reason_code,'NONE'),':',lock_version) ORDER BY id SEPARATOR '|')
            FROM lp_quote_costing_workspace
            """)).isEqualTo("1:STALE:QUOTE_BOM:BOM_RULE_CHANGED:4|"
                + "2:STALE:QUOTE_BOM:BOM_RULE_CHANGED:9|3:WAIT_BOM:QUOTE_BOM:NONE:0");
        assertThat(singleValue(statement,
            "SELECT current_cost_version_id FROM lp_quote_costing_workspace WHERE id=2"))
            .isEqualTo("99");

        statement.execute("""
            UPDATE lp_quote_costing_workspace SET workspace_status='BOM_READY',
              current_step='PRICE_TYPE_CONFIRMATION',stale_reason_code=NULL,
              current_bom_build_batch_id='NEW-DRAFT',lock_version=5 WHERE id=1
            """);
        applyMigration(connection);
        assertThat(readRule(statement)).isEqualTo(newRule);
        assertThat(singleValue(statement,
            "SELECT CONCAT(workspace_status,':',lock_version) FROM lp_quote_costing_workspace WHERE id=1"))
            .isEqualTo("BOM_READY:5");
      }
    }
  }

  private JsonNode readRule(Statement statement) throws Exception {
    return objectMapper.readTree(singleValue(statement,
        "SELECT match_condition_json FROM lp_bom_settlement_rule "
            + "WHERE rule_code='SPECIAL_PURCHASE_ROLLUP_FINANCE_CLASSIFICATION'"));
  }

  private static void applyMigration(Connection connection) {
    ScriptUtils.executeSqlScript(connection,
        new ClassPathResource("db/V259__special_purchase_rollup_child_criteria.sql"));
  }

  private static String singleValue(Statement statement, String sql) throws Exception {
    try (ResultSet result = statement.executeQuery(sql)) {
      assertThat(result.next()).isTrue();
      return result.getString(1);
    }
  }
}
