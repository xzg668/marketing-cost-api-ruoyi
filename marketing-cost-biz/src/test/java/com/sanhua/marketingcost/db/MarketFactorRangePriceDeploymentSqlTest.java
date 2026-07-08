package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MFRP-10 部署脚本与上线检查")
class MarketFactorRangePriceDeploymentSqlTest {

  private static final String MIGRATION_SQL = readResourceSql();
  private static final String DEPLOY_SCHEMA_SQL =
      readDeployFile("mfrp_01_market_factor_range_price_schema_2026-07-02.sql");
  private static final String ONLINE_SQL = readDeployFile("online_01_structure_only_2026-06-22.sql");
  private static final String CHECK_SQL =
      readDeployFile("mfrp_10_market_factor_range_price_online_checks_2026-07-02.sql");
  private static final String CHECKLIST = readDeployFile("上线数据初始化检查清单.md");

  @Test
  @DisplayName("V177 migration 纳入后端 resources/db")
  void migrationIsPackagedInBackendResources() {
    assertThat(MIGRATION_SQL).contains(
        "V177: 行情因素区间价结构",
        "CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule`",
        "CALL v177_add_column_if_not_exists(",
        "CALL v177_add_index_if_not_exists(");
  }

  @Test
  @DisplayName("旧库升级 SQL 纳入 deploy 且与 V177 保持一致")
  void deploySchemaUpgradeSqlMatchesMigration() {
    assertThat(DEPLOY_SCHEMA_SQL).isEqualTo(MIGRATION_SQL);
  }

  @Test
  @DisplayName("online_01 包含新库完整结构和旧库字段补丁")
  void onlineStructureSqlContainsFullSchemaAndPatch() {
    assertThat(ONLINE_SQL).contains(
        "CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule`",
        "`range_basis` varchar(16) NOT NULL DEFAULT 'QTY'",
        "`factor_rule_id` bigint DEFAULT NULL",
        "`factor_code` varchar(32) DEFAULT NULL",
        "`import_batch_no` varchar(64) DEFAULT NULL",
        "`current_flag` tinyint NOT NULL DEFAULT '1'",
        "KEY `idx_range_factor_current` (`business_unit_type`, `material_code`, `factor_code`, `current_flag`)",
        "Market factor range price schema");
  }

  @Test
  @DisplayName("online_01 与 V177 migration 的关键结构补丁保持一致")
  void onlineStructureSqlKeepsKeyPatchInSyncWithMigration() {
    assertContainsInBoth(
        "CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule`",
        "`factor_code` VARCHAR(32) NOT NULL COMMENT '影响因素编码: CU/ZN/AL/GOLD等'",
        "KEY `idx_factor_rule_current` (`business_unit_type`, `material_code`, `current_flag`)",
        "CALL v177_add_column_if_not_exists(\n  'lp_price_range_item',\n  'range_basis'",
        "CALL v177_add_column_if_not_exists(\n  'lp_price_range_item',\n  'factor_rule_id'",
        "CALL v177_add_column_if_not_exists(\n  'lp_price_range_item',\n  'factor_code'",
        "CALL v177_add_column_if_not_exists(\n  'lp_price_range_item',\n  'import_batch_no'",
        "CALL v177_add_column_if_not_exists(\n  'lp_price_range_item',\n  'current_flag'",
        "UPDATE `lp_price_range_item`\n   SET `range_basis` = 'QTY'",
        "UPDATE `lp_price_range_item`\n   SET `current_flag` = 1",
        "CALL v177_add_index_if_not_exists(\n  'lp_price_range_item',\n  'idx_range_factor_current'",
        "CALL v177_add_index_if_not_exists(\n  'lp_price_range_item',\n  'idx_range_factor_rule'",
        "CALL v177_add_index_if_not_exists(\n  'lp_price_range_item',\n  'idx_range_basis'");
  }

  @Test
  @DisplayName("上线检查 SQL 覆盖规则数量、重复当前规则、孤儿明细和区间重叠")
  void onlineCheckSqlCoversRequiredRiskQueries() {
    assertThat(CHECK_SQL).contains(
        "COUNT(*) AS factor_rule_count",
        "COUNT(*) AS current_factor_rule_count",
        "GROUP BY business_unit_type, material_code",
        "HAVING COUNT(*) > 1",
        "COUNT(*) AS orphan_factor_items",
        "LEFT JOIN lp_price_range_factor_rule r",
        "WHERE i.range_basis = 'FACTOR'",
        "i1.range_low <= i2.range_high",
        "i2.range_low <= i1.range_high",
        "WHERE range_basis = 'FACTOR'");
  }

  @Test
  @DisplayName("上线检查清单包含 MFRP 检查入口和代码回滚说明")
  void checklistDocumentsOnlineChecksAndRollback() {
    assertThat(CHECKLIST).contains(
        "deploy/mfrp_01_market_factor_range_price_schema_2026-07-02.sql",
        "deploy/mfrp_10_market_factor_range_price_online_checks_2026-07-02.sql",
        "检查行情因素区间价结构和数据",
        "Unknown column 'range_basis' in 'field list'",
        "orphan_factor_items",
        "当前有效行情因素区间价明细不能重叠",
        "行情因素区间价回滚说明",
        "代码回滚时不回滚 MFRP 结构字段",
        "回滚后必须暂停使用“行情因素区间价”功能",
        "保留这些字段不影响旧数量区间价逻辑");
  }

  private static void assertContainsInBoth(String... fragments) {
    for (String fragment : fragments) {
      assertThat(MIGRATION_SQL).contains(fragment);
      assertThat(ONLINE_SQL).contains(fragment);
    }
  }

  private static String readResourceSql() {
    try (var in = MarketFactorRangePriceDeploymentSqlTest.class.getResourceAsStream(
        "/db/V177__market_factor_range_price_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V177 SQL 失败", e);
    }
  }

  private static String readDeployFile(String fileName) {
    Path path = Path.of("..", "..", "deploy", fileName);
    if (!Files.exists(path)) {
      path = Path.of("deploy", fileName);
    }
    assertThat(Files.exists(path)).as(fileName + " exists").isTrue();
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 deploy 文件失败: " + fileName, e);
    }
  }
}
