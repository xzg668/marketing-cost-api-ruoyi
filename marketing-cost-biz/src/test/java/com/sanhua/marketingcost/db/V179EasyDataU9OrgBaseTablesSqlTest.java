package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V179 EasyData U9 基础表组织维度")
class V179EasyDataU9OrgBaseTablesSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("三张 BOM/副产品正式表补 price_org_code 且不默认商用")
  void addsPriceOrgCodeColumns() {
    assertThat(SQL).contains(
        "CALL v179_add_column_if_not_exists(",
        "'lp_bom_u9_source'",
        "'lp_bom_raw_hierarchy'",
        "'lp_u9_bom_byproduct_master'",
        "'price_org_code'",
        "price_org_code VARCHAR(32) DEFAULT NULL",
        "CALL v179_modify_column_if_exists(");
    assertThat(SQL)
        .doesNotContain("price_org_code VARCHAR(64)")
        .doesNotContain("DEFAULT ''210''")
        .doesNotContain("SET price_org_code = '210'");
  }

  @Test
  @DisplayName("U9 source 唯一键和查询索引包含组织")
  void sourceIndexesIncludePriceOrg() {
    assertThat(SQL).contains(
        "CALL v179_drop_index_if_exists('lp_bom_u9_source', 'uk_u9_source_business')",
        "uk_u9_source_org_business",
        "UNIQUE KEY uk_u9_source_org_business (price_org_code, parent_material_no, child_material_no, bom_purpose, child_seq, bom_version, effective_from, effective_to)",
        "idx_org_parent_purpose_effective",
        "KEY idx_org_parent_purpose_effective (price_org_code, parent_material_no, bom_purpose, effective_to)",
        "idx_org_import_batch",
        "KEY idx_org_import_batch (price_org_code, import_batch_id)",
        "KEY idx_child (child_material_no)");
  }

  @Test
  @DisplayName("raw hierarchy 唯一键和查询索引包含组织")
  void rawHierarchyIndexesIncludePriceOrg() {
    assertThat(SQL).contains(
        "CALL v179_drop_index_if_exists('lp_bom_raw_hierarchy', 'uk_node_source_line')",
        "uk_node_org_source_line",
        "UNIQUE KEY uk_node_org_source_line (top_product_code, price_org_code, source_type, bom_purpose, effective_from, source_line_key)",
        "idx_org_top_material",
        "KEY idx_org_top_material (price_org_code, top_product_code, material_code)",
        "idx_org_top_parent",
        "KEY idx_org_top_parent (price_org_code, top_product_code, parent_code)",
        "idx_org_top_path",
        "KEY idx_org_top_path (price_org_code, top_product_code, path)",
        "idx_org_effective",
        "KEY idx_org_effective (price_org_code, effective_from, effective_to)");
  }

  @Test
  @DisplayName("副产品唯一键和查询索引包含组织")
  void byproductIndexesIncludePriceOrg() {
    assertThat(SQL).contains(
        "CALL v179_drop_index_if_exists('lp_u9_bom_byproduct_master', 'uk_u9_bom_byproduct_natural')",
        "uk_u9_bom_byproduct_org_natural",
        "UNIQUE KEY uk_u9_bom_byproduct_org_natural (price_org_code, bom_purpose, parent_material_no, byproduct_material_no, effective_from, effective_to)",
        "idx_org_parent_effective",
        "KEY idx_org_parent_effective (price_org_code, parent_material_no, bom_purpose, effective_from, effective_to)");
  }

  @Test
  @DisplayName("迁移脚本为结构变更，不清空或删除正式基础表数据")
  void doesNotClearFormalBaseTables() {
    assertThat(SQL)
        .doesNotContain("DROP TABLE lp_bom_u9_source")
        .doesNotContain("DROP TABLE lp_bom_raw_hierarchy")
        .doesNotContain("DROP TABLE lp_u9_bom_byproduct_master")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM lp_bom_u9_source")
        .doesNotContain("DELETE FROM lp_bom_raw_hierarchy")
        .doesNotContain("DELETE FROM lp_u9_bom_byproduct_master");
  }

  @Test
  @DisplayName("脚本具备幂等辅助过程")
  void usesIdempotentHelpers() {
    assertThat(SQL).contains(
        "CREATE PROCEDURE v179_add_column_if_not_exists",
        "CREATE PROCEDURE v179_modify_column_if_exists",
        "CREATE PROCEDURE v179_drop_index_if_exists",
        "CREATE PROCEDURE v179_add_index_if_not_exists",
        "information_schema.COLUMNS",
        "information_schema.STATISTICS",
        "DROP PROCEDURE IF EXISTS v179_add_column_if_not_exists",
        "DROP PROCEDURE IF EXISTS v179_modify_column_if_exists",
        "DROP PROCEDURE IF EXISTS v179_drop_index_if_exists",
        "DROP PROCEDURE IF EXISTS v179_add_index_if_not_exists");
  }

  private static String readSql() {
    try (var in = V179EasyDataU9OrgBaseTablesSqlTest.class.getResourceAsStream(
        "/db/V179__easydata_u9_org_base_tables.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V179 SQL 失败", e);
    }
  }
}
