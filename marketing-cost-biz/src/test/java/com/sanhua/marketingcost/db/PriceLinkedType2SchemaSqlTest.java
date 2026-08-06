package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PLI2-01 类型2导入基础结构 SQL")
class PriceLinkedType2SchemaSqlTest {

  private static final String SQL = readMigrationSql();

  @Test
  @DisplayName("影响因素身份增加可空统一身份字段和非唯一索引")
  void extendsFactorIdentityWithNullableCanonicalFields() {
    assertThat(SQL).contains(
        "'canonical_factor_key'",
        "'VARCHAR(128) NULL COMMENT ''统一因素键，如 AVG|1#CU'''",
        "'canonical_factor_identity_id'",
        "'BIGINT NULL COMMENT ''统一主影响因素身份 ID；主身份可指向自身'''",
        "'identity_origin'",
        "'VARCHAR(32) NULL COMMENT ''身份来源：STANDARD_IMPORT/TYPE2_AUTO_CREATE'''",
        "idx_factor_identity_canonical_key",
        "(`business_unit_type`, `canonical_factor_key`)",
        "idx_factor_identity_canonical_master",
        "(`canonical_factor_identity_id`)");

    assertThat(SQL.toUpperCase()).doesNotContain("UNIQUE KEY");
  }

  @Test
  @DisplayName("联动价增加八个可空导入依据字段")
  void extendsLinkedItemWithNullableImportBasisFields() {
    assertThat(SQL).contains(
        "'source_upload_batch_id'",
        "'BIGINT NULL COMMENT ''类型 2 来源上传批次'''",
        "'source_sheet_name'",
        "'VARCHAR(128) NULL COMMENT ''业务计算 Sheet 名称'''",
        "'source_row_number'",
        "'INT NULL COMMENT ''业务计算 Sheet 的 1-based 行号'''",
        "'source_formula_cell_ref'",
        "'VARCHAR(32) NULL COMMENT ''现含税价公式单元格，如 R5'''",
        "'source_formula_expr'",
        "'TEXT NULL COMMENT ''Excel 原始公式，不做变量替换'''",
        "'source_input_snapshot_json'",
        "'JSON NULL COMMENT ''原始输入字段、单元格、值、单位和因素身份快照'''",
        "'source_tax_included_price'",
        "'DECIMAL(20,8) NULL COMMENT ''Excel 导入当时的现含税价'''",
        "'source_tax_excluded_price'",
        "'DECIMAL(20,8) NULL COMMENT ''Excel 导入当时的现不含税价'''",
        "idx_price_linked_item_source_trace",
        "(`source_upload_batch_id`, `source_sheet_name`, `source_row_number`)");
  }

  @Test
  @DisplayName("迁移只增加结构且不写入四类旧业务数据")
  void migrationDoesNotRewriteLegacyPriceData() {
    String upper = SQL.toUpperCase();
    assertThat(upper)
        .doesNotContain("UPDATE LP_PRICE_LINKED_ITEM")
        .doesNotContain("UPDATE `LP_PRICE_LINKED_ITEM`")
        .doesNotContain("UPDATE LP_PRICE_VARIABLE_BINDING")
        .doesNotContain("UPDATE `LP_PRICE_VARIABLE_BINDING`")
        .doesNotContain("UPDATE LP_FACTOR_MONTHLY_PRICE")
        .doesNotContain("UPDATE `LP_FACTOR_MONTHLY_PRICE`")
        .doesNotContain("DELETE FROM LP_PRICE_LINKED_ITEM")
        .doesNotContain("TRUNCATE TABLE LP_PRICE_LINKED_ITEM")
        .doesNotContain("INSERT INTO LP_PRICE_LINKED_ITEM")
        .doesNotContain("INSERT INTO LP_PRICE_VARIABLE_BINDING")
        .doesNotContain("INSERT INTO LP_FACTOR_MONTHLY_PRICE");
  }

  @Test
  @DisplayName("迁移过程对象执行后全部清理")
  void migrationDropsTemporaryProcedures() {
    assertThat(SQL).contains(
        "DROP PROCEDURE IF EXISTS v198_add_index_if_not_exists;",
        "DROP PROCEDURE IF EXISTS v198_add_column_if_not_exists;");
  }

  private static String readMigrationSql() {
    try (var in = PriceLinkedType2SchemaSqlTest.class.getResourceAsStream(
        "/db/V198__price_linked_type2_import_basis.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new IllegalStateException("读取 V198 SQL 失败", ex);
    }
  }
}
