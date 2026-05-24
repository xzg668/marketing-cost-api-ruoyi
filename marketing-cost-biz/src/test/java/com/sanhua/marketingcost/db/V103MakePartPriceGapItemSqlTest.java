package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V103 制造件价格缺口清单 DDL")
class V103MakePartPriceGapItemSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("主明细增加价格月份和完整取价标识")
  void addsCalcRowPriceContextColumns() {
    assertThat(SQL).contains(
        "lp_make_part_price_calc_row",
        "pricing_month",
        "VARCHAR(7) DEFAULT NULL COMMENT '价格月份 YYYY-MM；为空表示历史生成数据'",
        "price_complete",
        "TINYINT(1) NOT NULL DEFAULT 0 COMMENT '是否完整取到原材料价和废料价：1 是，0 否'");
  }

  @Test
  @DisplayName("新增结构化缺价清单表")
  void createsGapItemTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_make_part_price_gap_item",
        "parent_material_no VARCHAR(64) NOT NULL COMMENT '制造件料号/原始料号'",
        "child_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原材料/毛坯料号'",
        "scrap_code VARCHAR(64) NOT NULL DEFAULT '' COMMENT '回收废料料号'",
        "missing_price_role VARCHAR(16) NOT NULL COMMENT '缺价类型：RAW 原材料价 / SCRAP 废料价'",
        "missing_material_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '真正要补价的料号'",
        "oa_push_status VARCHAR(16) NOT NULL DEFAULT 'NOT_PUSHED'");
  }

  @Test
  @DisplayName("唯一键按批次、制造件、原材料、废料和缺价类型去重")
  void definesGapUniqueKey() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_make_gap_batch_role",
        "calc_batch_id",
        "parent_material_no",
        "child_material_no",
        "scrap_code",
        "missing_price_role");
  }

  @Test
  @DisplayName("只做数据沉淀，不触发 OA 推送或破坏历史数据")
  void doesNotPushOaOrDestroyData() {
    assertThat(SQL)
        .contains("本表只沉淀后续 OA 补价需要的数据，不在本次迁移和本阶段业务中触发 OA 推送")
        .contains("CREATE TABLE IF NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM");
  }

  private static String readSql() {
    try (var in = V103MakePartPriceGapItemSqlTest.class.getResourceAsStream(
        "/db/V103__make_part_price_gap_item.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V103 SQL 失败", e);
    }
  }
}
