package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class V223PricePrepareGapMaterialNameSqlTest {

  @Test
  @DisplayName("V223 为价格缺口增加品名快照并回填历史数据")
  void migrationAddsAndBackfillsMaterialName() throws IOException {
    String sql;
    try (InputStream input = getClass().getResourceAsStream(
        "/db/V223__price_prepare_gap_material_name.sql")) {
      assertThat(input).isNotNull();
      sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
        .contains("'material_name'")
        .contains("缺口料号品名快照")
        .contains("UPDATE lp_price_prepare_gap g")
        .contains("JOIN lp_bom_costing_row b")
        .contains("JOIN lp_price_prepare_item i")
        .contains("COALESCE(NULLIF(g.gap_material_code, ''), g.material_code)");
  }
}
