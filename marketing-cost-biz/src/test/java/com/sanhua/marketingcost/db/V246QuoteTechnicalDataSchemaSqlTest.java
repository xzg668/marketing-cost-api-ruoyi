package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

@DisplayName("V246 技术资料工作台 SQL 契约")
class V246QuoteTechnicalDataSchemaSqlTest {
  private static final String SQL = readSql();
  private static final Set<String> EXPECTED_TABLES = Set.of(
      "lp_quote_tech_task",
      "lp_quote_tech_product",
      "lp_quote_tech_module",
      "lp_quote_tech_data_version",
      "lp_quote_tech_package_item",
      "lp_quote_tech_aux_item",
      "lp_quote_tech_salary_item",
      "lp_quote_tech_review_item");

  @Test
  @DisplayName("迁移精确创建设计中的8张表")
  void createsExactlyEightDesignedTables() {
    Matcher matcher = Pattern.compile(
        "(?i)CREATE\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+`?([a-z0-9_]+)`?")
        .matcher(SQL);
    Set<String> tables = new LinkedHashSet<>();
    while (matcher.find()) tables.add(matcher.group(1));
    assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED_TABLES);
    assertThat(tables).hasSize(8);
  }

  @Test
  @DisplayName("活动、版本、模块和审核轮次唯一约束完整")
  void declaresRequiredUniqueConstraints() {
    assertThat(SQL).contains(
        "UNIQUE KEY `uk_quote_tech_task_active_lock` (`active_lock_key`)",
        "UNIQUE KEY `uk_quote_tech_product_active_lock` (`active_lock_key`)",
        "UNIQUE KEY `uk_quote_tech_module_product_type` (`product_id`, `module_type`)",
        "UNIQUE KEY `uk_quote_tech_version_product_no` (`product_id`, `version_no`)",
        "UNIQUE KEY `uk_quote_tech_package_version_line` (`version_id`, `line_no`)",
        "UNIQUE KEY `uk_quote_tech_aux_version_line` (`version_id`, `line_no`)",
        "UNIQUE KEY `uk_quote_tech_salary_version_line` (`version_id`, `line_no`)",
        "UNIQUE KEY `uk_quote_tech_review_round_module`");
  }

  @Test
  @DisplayName("三类明细的数量、工时、单价和金额均为20位8位小数")
  void usesHighPrecisionDecimalsForDetailValues() {
    assertThat(SQL).contains(
        "`quantity` DECIMAL(20,8) NOT NULL",
        "`standard_quantity` DECIMAL(20,8) NOT NULL",
        "`reference_unit_price` DECIMAL(20,8)",
        "`working_hours` DECIMAL(20,8) NOT NULL",
        "`standard_hours` DECIMAL(20,8) NOT NULL",
        "`hourly_rate` DECIMAL(20,8) NOT NULL",
        "`amount` DECIMAL(20,8)",
        "`package_total_amount` DECIMAL(20,8)",
        "`auxiliary_total_amount` DECIMAL(20,8)",
        "`salary_total_amount` DECIMAL(20,8)");
    assertThat(SQL).doesNotContainIgnoringCase(" FLOAT", " DOUBLE");
  }

  @Test
  @DisplayName("产品快照、模块参照和审核证据使用结构化字段")
  void preservesSnapshotsAndTraceability() {
    assertThat(SQL).contains(
        "`source_snapshot_json` JSON NOT NULL",
        "`source_fingerprint` CHAR(64) NOT NULL",
        "`reference_snapshot_json` JSON DEFAULT NULL",
        "`content_fingerprint` CHAR(64) DEFAULT NULL",
        "`difference_snapshot_json` JSON DEFAULT NULL",
        "`validation_snapshot_json` JSON DEFAULT NULL");
  }

  @Test
  @DisplayName("迁移可重跑且不碰旧协作表")
  void isRerunnableAndDoesNotMutateLegacyTables() {
    assertThat(SQL).contains("CREATE TABLE IF NOT EXISTS");
    assertThat(SQL)
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("ALTER TABLE lp_quote_collaboration")
        .doesNotContain("DELETE FROM lp_quote_collaboration")
        .doesNotContain("UPDATE lp_quote_collaboration");
  }

  private static String readSql() {
    try {
      return new ClassPathResource("db/V246__quote_technical_data_workspace.sql")
          .getContentAsString(StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V246 SQL 失败", exception);
    }
  }
}
