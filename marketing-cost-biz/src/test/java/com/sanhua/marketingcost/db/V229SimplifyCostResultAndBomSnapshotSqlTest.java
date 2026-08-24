package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V229SimplifyCostResultAndBomSnapshotSqlTest {

  @Test
  void migratesHistoricalDataBeforeDroppingDuplicateTablesAndFreezeFields()
      throws IOException {
    String sql =
        new ClassPathResource("db/V229__simplify_cost_result_and_bom_snapshot.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("UPDATE `lp_quote_cost_run_version` v")
        .contains("INSERT INTO `lp_quote_cost_run_version`")
        .contains("HAVING COUNT(*) = 1")
        .contains("COALESCE(v.`oa_form_item_id`, p.`oa_form_item_id`)")
        .contains("COALESCE(v.`oa_form_item_id`, c.`oa_form_item_id`)")
        .contains("UPDATE `lp_cost_run_part_item` p")
        .contains("UPDATE `lp_cost_run_cost_item` c")
        .contains("INSERT INTO `lp_quote_costing_workspace`")
        .contains("DROP TABLE IF EXISTS `lp_quote_writeback_task`")
        .contains("DROP TABLE IF EXISTS `lp_cost_run_result`")
        .contains("CHANGE COLUMN `source_cost_result_id` `source_cost_version_id`")
        .contains("idx_reprice_result_source_version")
        .contains("DROP COLUMN `freeze_status`")
        .contains("DROP COLUMN `effective_build_batch_id`")
        .contains("DROP COLUMN `effective_variant_hash`")
        .contains("DROP COLUMN `inherited_monthly_snapshot_id`");

    assertThat(sql.indexOf("INSERT INTO `lp_quote_cost_run_version`"))
        .isLessThan(sql.indexOf("DROP TABLE IF EXISTS `lp_cost_run_result`"));
    assertThat(sql.indexOf("INSERT INTO `lp_quote_costing_workspace`"))
        .isLessThan(sql.indexOf("DROP COLUMN `effective_build_batch_id`"));
  }
}
