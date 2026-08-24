package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V222 修正制造件父子净重单位")
class V222FixMakePartChildNetWeightUnitSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("按副产品千克转克后修正A板片组件两条材料净重")
  void fixesPlateComponentWeightsWithKgToGramConversion() {
    assertThat(SQL)
        .contains("child_material_no = '301240299'")
        .contains("net_weight_g = 33.60000000")
        .contains("毛重37.9g-副产品0.0043kg*1000=净重33.6g")
        .contains("child_material_no = '301070047'")
        .contains("net_weight_g = 5.00000000")
        .contains("毛重5.6g-副产品0.0006kg*1000=净重5.0g")
        .contains("source_type = 'U9_BYPRODUCT_RECALC'");
  }

  private static String readSql() {
    try (var in = V222FixMakePartChildNetWeightUnitSqlTest.class.getResourceAsStream(
        "/db/V222__fix_make_part_child_net_weight_unit.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V222 SQL 失败", e);
    }
  }
}
