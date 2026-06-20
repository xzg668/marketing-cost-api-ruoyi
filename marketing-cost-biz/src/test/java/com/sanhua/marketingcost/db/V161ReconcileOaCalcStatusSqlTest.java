package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V161ReconcileOaCalcStatusSqlTest {

  @Test
  void reconcilesOaHeaderCalcStatusFromItemAggregate() throws Exception {
    String sql =
        Files.readString(
            Path.of("src/main/resources/db/V161__reconcile_oa_calc_status_from_items.sql"),
            StandardCharsets.UTF_8);

    assertThat(sql).contains("UPDATE oa_form f");
    assertThat(sql).contains("FROM oa_form_item i");
    assertThat(sql).contains("COUNT(*) AS item_count");
    assertThat(sql).contains("SUM(CASE WHEN i.calc_status = '已核算' THEN 1 ELSE 0 END)");
    assertThat(sql).contains("THEN '已核算'");
    assertThat(sql).contains("ELSE '未核算'");
    assertThat(sql).contains("f.calc_at = CASE");
  }
}
