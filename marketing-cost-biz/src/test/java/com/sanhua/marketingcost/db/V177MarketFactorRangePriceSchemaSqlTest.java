package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("MFRP-01 行情因素区间价结构 SQL")
class V177MarketFactorRangePriceSchemaSqlTest {

  private static final String SQL = readResourceSql();
  private static final String ONLINE_SQL = readOnlineSql();

  @Test
  @DisplayName("新增行情因素区间价规则表")
  void createsFactorRuleTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule`",
        "`material_code` VARCHAR(64) NOT NULL",
        "`factor_code` VARCHAR(32) NOT NULL",
        "`version_no` INT NOT NULL DEFAULT 1",
        "`import_batch_no` VARCHAR(64) NOT NULL",
        "`effective_from` DATE NOT NULL",
        "`current_flag` TINYINT NOT NULL DEFAULT 1",
        "KEY `idx_factor_rule_current` (`business_unit_type`, `material_code`, `current_flag`)");
  }

  @Test
  @DisplayName("扩展区间价明细字段并回填旧数据兼容默认值")
  void extendsRangeItemForFactorBasis() {
    assertThat(SQL).contains(
        "'range_basis'",
        "VARCHAR(16) NOT NULL DEFAULT ''QTY''",
        "'factor_rule_id'",
        "BIGINT DEFAULT NULL",
        "'factor_code'",
        "VARCHAR(32) DEFAULT NULL",
        "'import_batch_no'",
        "VARCHAR(64) DEFAULT NULL",
        "'current_flag'",
        "TINYINT NOT NULL DEFAULT 1",
        "SET `range_basis` = 'QTY'",
        "SET `current_flag` = 1");
  }

  @Test
  @DisplayName("新增行情因素区间价查询索引")
  void addsRangeFactorIndexes() {
    assertThat(SQL).contains(
        "idx_range_factor_current",
        "KEY `idx_range_factor_current` (`business_unit_type`, `material_code`, `factor_code`, `current_flag`)",
        "idx_range_factor_rule",
        "KEY `idx_range_factor_rule` (`factor_rule_id`)",
        "idx_range_basis",
        "KEY `idx_range_basis` (`range_basis`)");
  }

  @Test
  @DisplayName("迁移脚本只增补结构，不删除或清空历史区间价")
  void migrationDoesNotDropOrClearRangePriceData() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE `lp_price_range_item`")
        .doesNotContain("DROP TABLE lp_price_range_item")
        .doesNotContain("TRUNCATE TABLE `lp_price_range_item`")
        .doesNotContain("TRUNCATE TABLE lp_price_range_item")
        .doesNotContain("DELETE FROM `lp_price_range_item`")
        .doesNotContain("DELETE FROM lp_price_range_item");
  }

  @Test
  @DisplayName("online_01 同步包含新表、字段、索引和旧库补丁")
  void onlineStructureSqlIncludesMfrp01Schema() {
    assertThat(ONLINE_SQL).contains(
        "CREATE TABLE IF NOT EXISTS `lp_price_range_factor_rule`",
        "`range_basis` varchar(16) NOT NULL DEFAULT 'QTY'",
        "`factor_rule_id` bigint DEFAULT NULL",
        "`factor_code` varchar(32) DEFAULT NULL",
        "`import_batch_no` varchar(64) DEFAULT NULL",
        "`current_flag` tinyint NOT NULL DEFAULT '1'",
        "KEY `idx_range_factor_current` (`business_unit_type`, `material_code`, `factor_code`, `current_flag`)",
        "CALL v177_add_column_if_not_exists(",
        "CALL v177_add_index_if_not_exists(");
  }

  private static String readResourceSql() {
    try (var in = V177MarketFactorRangePriceSchemaSqlTest.class.getResourceAsStream(
        "/db/V177__market_factor_range_price_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V177 SQL 失败", e);
    }
  }

  private static String readOnlineSql() {
    Path path = Path.of("..", "..", "deploy", "online_01_structure_only_2026-06-22.sql");
    if (!Files.exists(path)) {
      path = Path.of("deploy", "online_01_structure_only_2026-06-22.sql");
    }
    assertThat(Files.exists(path)).as("online_01 structure sql exists").isTrue();
    try {
      return Files.readString(path, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 online_01 SQL 失败", e);
    }
  }
}
