package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V186 制造件父子材料净重")
class V186MakePartChildNetWeightSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("净重按组织、父子料号、BOM版本和月份唯一维护")
  void definesParentChildBusinessKey() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS lp_make_part_child_net_weight")
        .contains("material_organization_code")
        .contains("parent_material_no")
        .contains("child_material_no")
        .contains("bom_version")
        .contains("period_month")
        .contains("net_weight_g DECIMAL(20,8) NOT NULL");
  }

  @Test
  @DisplayName("A板片组件两条材料分别初始化见机表净重")
  void seedsPlateComponentChildWeights() {
    assertThat(SQL)
        .contains("'1053000301687', '301240299', 'F001', '2026-07', 37.89999570")
        .contains("'1053000301687', '301070047', 'F001', '2026-07', 5.59999940");
  }

  private static String readSql() {
    try (var in = V186MakePartChildNetWeightSqlTest.class.getResourceAsStream(
        "/db/V186__make_part_child_net_weight.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V186 SQL 失败", e);
    }
  }
}
