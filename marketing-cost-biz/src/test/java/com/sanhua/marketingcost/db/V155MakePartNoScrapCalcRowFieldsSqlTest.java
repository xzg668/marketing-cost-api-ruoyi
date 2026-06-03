package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V155 制造件无废料确认计算行字段")
class V155MakePartNoScrapCalcRowFieldsSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增无废料确认标记和确认记录ID字段")
  void addsNoScrapConfirmationFields() {
    assertThat(SQL).contains(
        "lp_make_part_price_calc_row",
        "no_scrap_confirmed",
        "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否人工确认无废料并按0抵扣：1 是，0 否'",
        "no_scrap_confirmation_id",
        "BIGINT DEFAULT NULL COMMENT '无废料人工确认记录ID，关联 lp_make_part_no_scrap_confirmation.id'");
  }

  @Test
  @DisplayName("不创建虚拟废料料号，不破坏历史数据")
  void doesNotCreateVirtualScrapCodeOrDestroyData() {
    assertThat(SQL)
        .contains("不把“确认无废料”写成虚拟 scrap_code")
        .doesNotContain("__NO_SCRAP__")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM");
  }

  private static String readSql() {
    try (var in = V155MakePartNoScrapCalcRowFieldsSqlTest.class.getResourceAsStream(
        "/db/V155__make_part_no_scrap_calc_row_fields.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V155 SQL 失败", e);
    }
  }
}
