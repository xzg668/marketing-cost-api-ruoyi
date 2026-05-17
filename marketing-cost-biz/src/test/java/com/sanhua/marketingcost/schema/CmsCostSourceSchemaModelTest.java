package com.sanhua.marketingcost.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class CmsCostSourceSchemaModelTest {

  private static final Path MIGRATION =
      Path.of("src/main/resources/db/V64__cms_cost_source_schema.sql");
  private static final Path DROP_AUX_CONFIG_MIGRATION =
      Path.of("src/main/resources/db/V68__drop_cms_aux_subject_config.sql");

  @Test
  void migrationCreatesCmsSourceTablesWithoutAuxSubjectConfig() throws IOException {
    String sql = readMigration();

    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_cost_import_batch");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_plan_cost_raw");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_workshop_labor_raw");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_product_subject_cost_raw");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_cost_source_effective");
    assertThat(sql).contains("CREATE TABLE IF NOT EXISTS cms_cost_source_effective_log");
    assertThat(sql).doesNotContain("CREATE TABLE IF NOT EXISTS cms_aux_subject_config");

    assertThat(sql).contains("COMMENT='CMS成本数据技术导入记录'");
    assertThat(sql).contains("COMMENT='CMS产品计划成本汇总原始数据'");
    assertThat(sql).contains("COMMENT='CMS产品车间料工费汇总原始数据'");
    assertThat(sql).contains("COMMENT='CMS产品科目成本汇总原始数据'");
    assertThat(sql).contains("COMMENT='CMS料号年度成本来源生效表'");
    assertThat(sql).contains("COMMENT='CMS料号年度成本来源刷新日志'");
  }

  @Test
  void migrationUsesEffectiveSourceInsteadOfTargetTableLocking() throws IOException {
    String sql = readMigration();

    assertThat(sql).contains("effective_period VARCHAR(7) NOT NULL COMMENT '生效期间 yyyy-MM，由生效时间转换，用于匹配CMS来源期间'");
    assertThat(sql).contains("UNIQUE KEY uk_cms_effective_source (cost_year, source_type, parent_code, subject_code, business_unit_type)");
    assertThat(sql).contains("business_unit_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '业务单元类型：COMMERCIAL=商用，HOUSEHOLD=家用'");
    assertThat(sql).contains("_cms_cost_add_column_if_not_exists");
    assertThat(sql).contains("ADD COLUMN effective_period VARCHAR(7) NULL");
    assertThat(sql).contains("SET effective_period = DATE_FORMAT(effective_date, '%Y-%m')");
    assertThat(sql).contains("MODIFY effective_period VARCHAR(7) NOT NULL");
    assertThat(sql).contains("MODIFY source_row_id VARCHAR(80) NOT NULL");

    assertThat(sql).doesNotContain("CALL _cms_cost_add_column_if_not_exists('lp_salary_cost'");
    assertThat(sql).doesNotContain("CALL _cms_cost_add_column_if_not_exists('lp_aux_subject'");
    assertThat(sql).doesNotContain("uk_salary_cms_lock");
    assertThat(sql).doesNotContain("uk_aux_cms_lock");
  }

  @Test
  void migrationDocumentsCentToYuanFields() throws IOException {
    String sql = readMigration();

    assertThat(sql).contains("working_cost_cent DECIMAL(18,6) DEFAULT NULL COMMENT '工资，原始单位：分'");
    assertThat(sql).contains("working_cost_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '工资，转换后单位：元'");
    assertThat(sql).contains("material_price DECIMAL(18,6) DEFAULT NULL COMMENT '材料计价，CMS原始单位：分'");
    assertThat(sql).contains("material_price_yuan DECIMAL(18,6) DEFAULT NULL COMMENT '材料计价，转换后单位：元'");
    assertThat(sql).contains("source_row_id VARCHAR(80) NOT NULL COMMENT 'CMS原始id'");
    assertThat(sql).doesNotContain("INSERT INTO cms_aux_subject_config");
  }

  @Test
  void dropMigrationRemovesHistoricalAuxSubjectConfigTable() throws IOException {
    String sql = Files.readString(DROP_AUX_CONFIG_MIGRATION, StandardCharsets.UTF_8);

    assertThat(sql).contains("DROP TABLE IF EXISTS cms_aux_subject_config");
  }

  private static String readMigration() throws IOException {
    return Files.readString(MIGRATION, StandardCharsets.UTF_8);
  }
}
