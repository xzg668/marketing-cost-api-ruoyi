package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import com.sanhua.marketingcost.entity.MaterialScrapRef;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CmsMaterialScrapRefSchemaModelTest {

  private static final Path MIGRATION =
      Path.of("src/main/resources/db/V69__cms_material_scrap_ref.sql");
  private static final Path SCHEMA_REPAIR_MIGRATION =
      Path.of("src/main/resources/db/V73__cms_material_scrap_ref_schema_repair.sql");
  private static final Path MENU_REPAIR_MIGRATION =
      Path.of("src/main/resources/db/V71__cms_material_scrap_ref_menu_repair.sql");

  @Test
  void migrationExtendsCurrentMappingOnly() throws IOException {
    String sql = readMigration();

    assertThat(sql).doesNotContain("CREATE TABLE IF NOT EXISTS cms_material_scrap_ref_raw");
    assertThat(sql).doesNotContain("COMMENT='CMS原材料对应回收废料原始数据'");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS lp_material_scrap_ref");
    assertThat(sql).contains("_cms_material_scrap_add_column_if_not_exists");
    assertThat(sql).contains("ADD COLUMN material_name VARCHAR(180) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN scrap_name VARCHAR(180) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN cms_posting_period VARCHAR(7) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN cms_effective_date DATE DEFAULT NULL");
    assertThat(sql).doesNotContain("raw_json");
  }

  @Test
  void migrationKeepsOneToManyMappingByMaterialAndScrapCode() throws IOException {
    String sql = readMigration();

    assertThat(sql).contains("UNIQUE KEY uk_material_scrap (business_unit_type, material_code, scrap_code)");
    assertThat(sql).contains("ADD KEY idx_material_scrap_ref_bu_material (business_unit_type, material_code)");
    assertThat(sql).doesNotContain("source_raw_id");
    assertThat(sql).doesNotContain("recycle_code");
    assertThat(sql).doesNotContain("recycle_unit_price");
  }

  @Test
  void currentMappingEntityContainsCmsTraceColumns() {
    assertThat(fieldNames(MaterialScrapRef.class))
        .contains(
            "materialName",
            "materialSpec",
            "materialUnit",
            "scrapName",
            "scrapSpec",
            "scrapUnit",
            "sourceType",
            "sourceDocNo",
            "cmsRecordId",
            "linkDetailId",
            "cmsPostingPeriod",
            "cmsEffectiveDate",
            "approvalTime",
            "syncTime",
            "remark");
    assertThat(fieldNames(MaterialScrapRef.class)).doesNotContain("sourceRawId");
  }

  @Test
  void schemaRepairMigrationBackfillsMaterialScrapRefColumnsForAlreadyMigratedDatabases()
      throws IOException {
    String sql = Files.readString(SCHEMA_REPAIR_MIGRATION, StandardCharsets.UTF_8);

    assertThat(sql).contains("V69 初版已被 Flyway 标记执行");
    assertThat(sql).contains("ADD COLUMN material_name VARCHAR(180) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN scrap_name VARCHAR(180) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN source_doc_no VARCHAR(128) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN cms_posting_period VARCHAR(7) DEFAULT NULL");
    assertThat(sql).contains("ADD COLUMN remark VARCHAR(512) DEFAULT NULL");
    assertThat(sql).contains("idx_material_scrap_ref_bu_material");
  }

  @Test
  void menuRepairMigrationExposesMaterialScrapExcelImportEntry() throws IOException {
    String sql = Files.readString(MENU_REPAIR_MIGRATION, StandardCharsets.UTF_8);

    assertThat(sql).contains("(40420, 'CMS 回收废料映射', 40230, 4");
    assertThat(sql).contains("'/base/cms-cost/material-scrap-refs'");
    assertThat(sql).contains("'pages:CmsMaterialScrapRefPage'");
    assertThat(sql).contains("CMS原材料料号到回收废料料号的当前有效映射和Excel导入入口");
    assertThat(sql).contains("WHERE menu_id IN (40230, 40231, 40237, 40239)");
  }

  private static String readMigration() throws IOException {
    return Files.readString(MIGRATION, StandardCharsets.UTF_8);
  }

  private static Iterable<String> fieldNames(Class<?> type) {
    return java.util.Arrays.stream(type.getDeclaredFields())
        .map(Field::getName)
        .toList();
  }
}
