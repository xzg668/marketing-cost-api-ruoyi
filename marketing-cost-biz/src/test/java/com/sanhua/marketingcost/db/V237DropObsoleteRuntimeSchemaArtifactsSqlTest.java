package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class V237DropObsoleteRuntimeSchemaArtifactsSqlTest {

  @Test
  void migrationDropsOnlyVerifiedArtifactsAndKeepsExternalImportStagingTables()
      throws IOException {
    String sql =
        new ClassPathResource("db/V237__drop_obsolete_runtime_schema_artifacts.sql")
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(sql)
        .contains("DROP TABLE IF EXISTS `_moji_backup_20260429`")
        .contains("DROP TABLE IF EXISTS `lp_material_price_type_bak_20260518_excel`")
        .contains("DROP TABLE IF EXISTS `bom_stop_drill_rule`")
        .contains("DROP TABLE IF EXISTS `lp_bom_manage_item`")
        .contains("DROP TABLE IF EXISTS `lp_make_part_spec`")
        .contains("DROP TABLE IF EXISTS `lp_price_scrap`")
        .contains("DROP TABLE IF EXISTS `lp_raw_material_breakdown`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_lp_bom_raw_hierarchy`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_lp_bom_u9_source`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_lp_material_master_raw`")
        .doesNotContain("DROP TABLE IF EXISTS `tmp_lp_u9_bom_byproduct_master`");
  }
}
