package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V98 制造件价格生成明细表 DDL")
class V98MakePartPriceCalcRowSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增制造件价格生成明细表和核心业务字段")
  void createsMakePartPriceCalcRowTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_make_part_price_calc_row",
        "calc_batch_id VARCHAR(64) NOT NULL",
        "parent_material_no VARCHAR(64) NOT NULL",
        "child_material_no VARCHAR(64) NOT NULL",
        "item_process_type VARCHAR(32) NOT NULL",
        "parent_total_cost_price DECIMAL(20,8)");
  }

  @Test
  @DisplayName("中文注释明确重量为 g 且元/kg 计算要除以 1000")
  void documentsWeightAndPriceUnits() {
    assertThat(SQL).contains(
        "重量字段统一为 g",
        "必须先除以 1000 转 kg",
        "raw_unit_price：原材料加工为元/kg，毛坯加工为元/件",
        "scrap_unit_price：元/kg",
        "gross_weight_g DECIMAL(20,6) DEFAULT NULL COMMENT '毛重，单位 g'",
        "net_weight_g DECIMAL(20,6) DEFAULT NULL COMMENT '净重，单位 g'");
  }

  @Test
  @DisplayName("采购单价和回收单价注释按业务口径区分")
  void documentsRawAndScrapPriceSemantics() {
    assertThat(SQL).contains(
        "raw_unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '采购单价；原材料加工为元/kg，毛坯加工为元/件'",
        "scrap_unit_price DECIMAL(20,8) DEFAULT NULL COMMENT '回收单价，元/kg'",
        "outsource_fee DECIMAL(20,6) NOT NULL DEFAULT 0.000000 COMMENT '委外加工费；第一版不参与制造件公式'");
  }

  @Test
  @DisplayName("索引覆盖批次、父件、子件、废料和 OA 业务单元查询")
  void definesLookupIndexes() {
    assertThat(SQL).contains(
        "idx_make_calc_batch",
        "idx_make_calc_parent",
        "idx_make_calc_batch_parent",
        "idx_make_calc_child",
        "idx_make_calc_scrap",
        "idx_make_calc_oa_bu");
  }

  @Test
  @DisplayName("DDL 幂等且不做破坏性历史数据操作")
  void isIdempotentAndNonDestructive() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM");
  }

  private static String readSql() {
    try (var in = V98MakePartPriceCalcRowSqlTest.class.getResourceAsStream(
        "/db/V98__make_part_price_calc_row.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V98 SQL 失败", e);
    }
  }
}
