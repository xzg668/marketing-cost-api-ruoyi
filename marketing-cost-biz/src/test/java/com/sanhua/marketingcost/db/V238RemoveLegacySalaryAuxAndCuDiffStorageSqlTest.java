package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V238RemoveLegacySalaryAuxAndCuDiffStorageSqlTest {

  @Test
  void migrationRemovesTheWholeObsoleteStorageAndMenuChain() throws IOException {
    String sql =
        new ClassPathResource("db/V238__remove_legacy_salary_aux_and_cu_diff_storage.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("DROP TABLE IF EXISTS `lp_salary_cost`")
        .contains("DROP TABLE IF EXISTS `lp_aux_subject`")
        .contains("DROP TABLE IF EXISTS `lp_aux_rate_item`")
        .contains("DROP TABLE IF EXISTS `lp_quote_cu_material_diff_item`")
        .contains("DROP TABLE IF EXISTS `cms_cost_derive_log`")
        .contains("DROP COLUMN `salary_insert_count`")
        .contains("DROP COLUMN `salary_skip_count`")
        .contains("DROP COLUMN `salary_blocked_count`")
        .contains("DROP COLUMN `aux_insert_count`")
        .contains("DROP COLUMN `aux_skip_count`")
        .contains("tmp_v238_obsolete_menu_child")
        .doesNotContain(
            "INSERT IGNORE INTO `tmp_v238_obsolete_menu` (`menu_id`)\nSELECT child.`menu_id`")
        .contains("DELETE role_menu")
        .contains("DELETE menu");
  }
}
