package com.sanhua.marketingcost.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("V89 供应商供货比例 DDL")
class V89SupplierSupplyRatioSchemaSqlTest {

  private static final String SQL = readSql();

  @Test
  @DisplayName("新增供应商供货比例主数据表")
  void createsSupplierSupplyRatioTable() {
    assertThat(SQL).contains(
        "CREATE TABLE IF NOT EXISTS lp_supplier_supply_ratio",
        "material_code VARCHAR(64) NOT NULL",
        "material_name VARCHAR(180) NOT NULL",
        "spec_model VARCHAR(255) NOT NULL DEFAULT ''",
        "supplier_name VARCHAR(180) NOT NULL",
        "supply_ratio DECIMAL(18,6) NOT NULL DEFAULT 0.000000");
  }

  @Test
  @DisplayName("唯一键按用户确认业务键支撑多人多次导入去重")
  void definesBusinessUniqueKeyForImportIdempotency() {
    assertThat(SQL).contains(
        "UNIQUE KEY uk_supplier_ratio_biz",
        "business_unit_type",
        "material_code",
        "supplier_name",
        "deleted");
  }

  @Test
  @DisplayName("保留主供选择和来源追溯需要的索引字段")
  void keepsLookupAndTraceIndexes() {
    assertThat(SQL).contains(
        "idx_supplier_ratio_material",
        "idx_supplier_ratio_supplier",
        "idx_supplier_ratio_source",
        "source_type VARCHAR(32) NOT NULL DEFAULT 'EXCEL'",
        "source_batch_no VARCHAR(64)",
        "import_file_name VARCHAR(255)",
        "imported_by VARCHAR(64)",
        "imported_at DATETIME");
  }

  @Test
  @DisplayName("中文注释明确该表是供应关系主数据，不是价格源表")
  void documentsBusinessSemantics() {
    assertThat(SQL).contains(
        "供应关系主数据，不是价格源表",
        "后续 SRM 同步也写入同一张业务表",
        "取价时优先选择比例最大的供应商",
        "当前 Excel 可为空，后续 SRM 可补充");
  }

  @Test
  @DisplayName("DDL 幂等且不做破坏性操作")
  void isIdempotentAndNonDestructive() {
    assertThat(SQL)
        .contains("CREATE TABLE IF NOT EXISTS")
        .doesNotContain("DROP TABLE")
        .doesNotContain("TRUNCATE TABLE")
        .doesNotContain("DELETE FROM");
  }

  private static String readSql() {
    try (var in = V89SupplierSupplyRatioSchemaSqlTest.class.getResourceAsStream(
        "/db/V89__supplier_supply_ratio_schema.sql")) {
      assertThat(in).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取 V89 SQL 失败", e);
    }
  }
}
