package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V228DropObsoleteBackupTablesSqlTest {

  @Test
  void migrationDropsOnlyObsoleteBackupTablesAndKeepsImportStagingTables() throws IOException {
    String sql =
        new ClassPathResource("db/V228__drop_obsolete_backup_tables.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("DROP TABLE IF EXISTS `bak_lp_bom_raw_hierarchy_t9_20260708`")
        .contains("DROP TABLE IF EXISTS `bak_tmp_lp_material_master_raw_t8_20260708`")
        .contains("DROP TABLE IF EXISTS `lp_material_master_raw_copy1`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_lp_bom_raw_hierarchy`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_cms_plan_cost_raw`")
        .doesNotContain("DROP TABLE IF EXISTS `_moji_backup_20260429`");
  }
}
