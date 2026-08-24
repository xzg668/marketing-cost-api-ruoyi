package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V227 成本算法版本")
class V227QuoteCostAlgorithmVersionSqlTest {

  @Test
  @DisplayName("成本版本增加算法版本且旧数据默认标记为 LEGACY")
  void addsAlgorithmVersionWithLegacyDefault() {
    String sql = migrationSql();

    assertThat(sql)
        .contains("'lp_quote_cost_run_version'")
        .contains("'algorithm_version'")
        .contains("`algorithm_version` VARCHAR(64) NOT NULL DEFAULT ''LEGACY''")
        .contains("成本算法版本");
  }

  private String migrationSql() {
    try (var input = getClass().getResourceAsStream(
        "/db/V227__quote_cost_algorithm_version.sql")) {
      if (input == null) {
        throw new IllegalStateException("未找到 V227 迁移脚本");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("读取 V227 迁移脚本失败", exception);
    }
  }
}
