package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V133 月度调价解除影响因素批次必填")
class V133MonthlyRepriceDecoupleFactorAdjustBatchSqlTest {

  @Test
  void sqlMakesMonthlyRepriceAdjustBatchNullable() {
    String sql = readSql();

    assertThat(sql)
        .contains("V133")
        .contains("lp_monthly_reprice_batch")
        .contains("adjust_batch_id")
        .contains("BIGINT NULL")
        .contains("price_as_of_time");
  }

  private String readSql() {
    try (var in = V133MonthlyRepriceDecoupleFactorAdjustBatchSqlTest.class.getResourceAsStream(
        "/db/V133__monthly_reprice_decouple_factor_adjust_batch.sql")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V133 SQL 失败", e);
    }
  }
}
